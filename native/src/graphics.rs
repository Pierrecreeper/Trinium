//! Synchronous, ordered OpenGL submission. OpenGL object ownership stays with RenderPearl.
//! Function pointers come from the CURRENT LWJGL context, valid for this JNI call only.
use crate::gl_dispatch::{dispatch, FUNCTION_COUNT};
use std::ffi::c_void;
const MAX_COMMANDS: usize = 4096;
// Pad the pointer table to a whole record; matches RustGlDriver.FUNCTION_BYTES.
const FUNCTION_BYTES: usize = (FUNCTION_COUNT * 8 + 63) & !63;

unsafe fn direct_buffer<'a>(env: *mut c_void, object: *mut c_void) -> Option<&'a [u8]> {
    if env.is_null() || object.is_null() {
        return None;
    }
    let table = unsafe { *(env as *const *const *const c_void) };
    if table.is_null() {
        return None;
    }
    type Address = unsafe extern "system" fn(*mut c_void, *mut c_void) -> *mut c_void;
    type Capacity = unsafe extern "system" fn(*mut c_void, *mut c_void) -> i64;
    let address_ptr = unsafe { *table.add(230) };
    let capacity_ptr = unsafe { *table.add(231) };
    if address_ptr.is_null() || capacity_ptr.is_null() {
        return None;
    }
    let address: Address = unsafe { std::mem::transmute(address_ptr) };
    let capacity: Capacity = unsafe { std::mem::transmute(capacity_ptr) };
    let n = unsafe { capacity(env, object) };
    let p = unsafe { address(env, object) };
    if p.is_null() || n < 0 || n > isize::MAX as i64 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(p.cast(), n as usize) })
}
fn word(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(
        bytes[offset..offset + 8]
            .try_into()
            .expect("validated word"),
    )
}
fn validate(functions: &[u8], packet: &[u8], count: usize) -> bool {
    if functions.len() < FUNCTION_COUNT * 8 || count > MAX_COMMANDS || packet.len() < count * 64 {
        return false;
    }
    (0..count).all(|i| {
        let op = word(packet, i * 64);
        op < FUNCTION_COUNT as u64 && word(functions, op as usize * 8) != 0
    })
}
fn packed_slices(storage: &[u8]) -> Option<(&[u8], &[u8])> {
    if storage.len() < FUNCTION_BYTES {
        None
    } else {
        Some(storage.split_at(FUNCTION_BYTES))
    }
}
unsafe fn execute(functions: &[u8], packet: &[u8], count: usize) -> i32 {
    if !validate(functions, packet, count) {
        return -1;
    }
    // A direct ByteBuffer normally gives us aligned records on little-endian hosts.
    // Borrow its argument words instead of copying all seven words for every command:
    // the typed dispatcher only loads the arguments that its OpenGL call actually uses.
    // Validation above still covers the ENTIRE batch before the first GL side effect.
    #[cfg(target_endian = "little")]
    if packet.as_ptr().align_offset(std::mem::align_of::<u64>()) == 0 {
        // SAFETY: validate checked count*64 bytes and bounded count before multiplying.
        // Alignment was checked above, all u64 bit patterns are valid, and Java owns
        // this immutable packet for the synchronous call. Neither slice escapes.
        let records =
            unsafe { std::slice::from_raw_parts(packet.as_ptr().cast::<[u64; 8]>(), count) };
        for record in records {
            let op = record[0] as usize;
            // SAFETY: exactly seven aligned argument words follow the opcode.
            let args = unsafe { &*record.as_ptr().add(1).cast::<[u64; 7]>() };
            unsafe { dispatch(op, word(functions, op * 8) as usize, args) };
        }
        return 0;
    }
    // Portable path for unaligned direct-buffer slices and big-endian hosts.
    // Prevalidated, bounded, allocation-free; no fallible operation after the first GL call.
    for i in 0..count {
        let p = i * 64;
        let op = word(packet, p) as usize;
        let args = std::array::from_fn(|j| word(packet, p + 8 + j * 8));
        unsafe {
            dispatch(op, word(functions, op * 8) as usize, &args);
        }
    }
    0
}
#[cfg(test)]
#[path = "graphics_contract_tests.rs"]
mod contract_tests;
/// # Safety
/// The JVM passes exclusively owned direct buffers and current-context GL APIENTRY pointers.
/// Every GL pointer argument has the same lifetime/validity obligations as the LWJGL call
/// it replaces. No reference, function pointer, object name or buffer address is retained.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_pierrecreeper_trinium_graphics_RustGlDriver_execute0(
    env: *mut c_void,
    _class: *mut c_void,
    functions: *mut c_void,
    packet: *mut c_void,
    count: i32,
) -> i32 {
    if count < 0 {
        return -1;
    }
    let Some(functions) = (unsafe { direct_buffer(env, functions) }) else {
        return -1;
    };
    let Some(packet) = (unsafe { direct_buffer(env, packet) }) else {
        return -1;
    };
    unsafe { execute(functions, packet, count as usize) }
}
/// Single-buffer extension to ABI3; the original two-buffer entry point remains supported.
///
/// # Safety
/// As for execute0, Java owns the direct buffer throughout this synchronous call. The prefix
/// contains current-context APIENTRY pointers, followed by fixed-size command records. No
/// addresses are cached or retained, and all records are checked before the first GL call.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_pierrecreeper_trinium_graphics_RustGlDriver_executePacked0(
    env: *mut c_void,
    _class: *mut c_void,
    storage: *mut c_void,
    count: i32,
) -> i32 {
    if count < 0 {
        return -1;
    }
    let Some(storage) = (unsafe { direct_buffer(env, storage) }) else {
        return -1;
    };
    let Some((functions, packet)) = packed_slices(storage) else {
        return -1;
    };
    unsafe { execute(functions, packet, count as usize) }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_entire_invalid_packet_before_execution() {
        let functions = vec![1_u8; FUNCTION_COUNT * 8];
        let mut packet = vec![0_u8; 128];
        assert!(validate(&functions, &packet, 2));
        assert!(!validate(&functions[..16], &packet, 2));
        assert!(!validate(&functions, &packet[..127], 2));
        assert!(!validate(&functions, &packet, usize::MAX));
        packet[64..72].copy_from_slice(&u64::MAX.to_le_bytes());
        assert!(!validate(&functions, &packet, 2));
        assert!(!validate(&vec![0; FUNCTION_COUNT * 8], &packet, 1));
    }
    #[test]
    fn empty_batch_and_unaligned_bytes_are_supported() {
        let functions = vec![1_u8; FUNCTION_COUNT * 8 + 1];
        let packet = [0_u8; 65];
        assert!(validate(&functions[1..], &[], 0));
        assert!(validate(&functions[1..], &packet[1..], 1));
    }
    #[test]
    fn packed_layout_checks_header_and_full_packet_before_execution() {
        let mut storage = vec![0_u8; FUNCTION_BYTES + 2 * 64];
        storage[..FUNCTION_COUNT * 8].fill(1);
        assert!(packed_slices(&storage[..FUNCTION_BYTES - 1]).is_none());
        let (functions, packet) = packed_slices(&storage).unwrap();
        assert!(validate(functions, packet, 2));
        assert!(!validate(functions, packet, 3));
        assert!(!validate(functions, packet, usize::MAX));
        // Padding after the function table is not interpreted as a command.
        storage[FUNCTION_COUNT * 8..FUNCTION_BYTES].fill(255);
        let (functions, packet) = packed_slices(&storage).unwrap();
        assert!(validate(functions, packet, 2));
        storage[FUNCTION_BYTES + 64..FUNCTION_BYTES + 72]
            .copy_from_slice(&(FUNCTION_COUNT as u64).to_le_bytes());
        let (functions, packet) = packed_slices(&storage).unwrap();
        assert!(!validate(functions, packet, 2));
    }
}
