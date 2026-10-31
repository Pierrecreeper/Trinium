//! Trinium native CPU kernels and synchronous OpenGL command engine.
//! A section has 4096 cells indexed x | z<<4 | y<<8; opacity is 64 little-endian u64s.
//! Output is a 6x6 symmetric face-connectivity mask, not geometry and not an API for a GPU.
//! Face order: DOWN, UP, NORTH, SOUTH, WEST, EAST.
#![deny(unsafe_op_in_unsafe_fn)]

mod gl_dispatch;
mod graphics;

use std::cell::RefCell;
use std::ffi::c_void;

const MAX_QUADS: usize = 262_144;
thread_local! {
    static SORT_SCRATCH: RefCell<(Vec<u64>, Vec<u64>)> = const { RefCell::new((Vec::new(), Vec::new())) };
}

/// Ascending integer key for Java's descending Float.compare, including signed zero and NaN.
fn descending_float_key(bits: u32) -> u32 {
    let bits = if bits & 0x7fff_ffff > 0x7f80_0000 {
        0x7fc0_0000
    } else {
        bits
    };
    let ascending = if bits & 0x8000_0000 != 0 {
        !bits
    } else {
        bits ^ 0x8000_0000
    };
    !ascending
}

/// Four stable byte passes; ties preserve input order, as in Minecraft's merge sort.
fn radix_sort(data: &mut [u64], scratch: &mut [u64]) {
    for shift in [32, 40, 48, 56] {
        let mut counts = [0_usize; 256];
        for &entry in data.iter() {
            counts[((entry >> shift) & 255) as usize] += 1;
        }
        if counts.contains(&data.len()) {
            continue;
        }
        let mut sum = 0;
        for count in &mut counts {
            let n = *count;
            *count = sum;
            sum += n;
        }
        for &entry in data.iter() {
            let digit = ((entry >> shift) & 255) as usize;
            scratch[counts[digit]] = entry;
            counts[digit] += 1;
        }
        data.copy_from_slice(scratch);
    }
}

/// JNI input/output is a JVM-owned, writable direct ByteBuffer; no pointer survives the call.
///
/// # Safety
/// The JVM supplies valid JNI references. The caller must own a writable buffer exclusively
/// for this call (NativeRadix.sort enforces direct/writable/capacity/count constraints).
#[no_mangle]
pub unsafe extern "system" fn Java_fr_pierrecreeper_trinium_NativeRadix_sort0(
    env: *mut c_void,
    _class: *mut c_void,
    buffer: *mut c_void,
    count: i32,
) -> i32 {
    if env.is_null() || buffer.is_null() || count < 0 || count as usize > MAX_QUADS {
        return -1;
    }
    let count = count as usize;
    let table = unsafe { *(env as *const *const *const c_void) };
    if table.is_null() {
        return -1;
    }
    type GetAddress = unsafe extern "system" fn(*mut c_void, *mut c_void) -> *mut c_void;
    type GetCapacity = unsafe extern "system" fn(*mut c_void, *mut c_void) -> i64;
    let address_ptr = unsafe { *table.add(230) };
    let capacity_ptr = unsafe { *table.add(231) };
    if address_ptr.is_null() || capacity_ptr.is_null() {
        return -1;
    }
    let get_address: GetAddress = unsafe { std::mem::transmute(address_ptr) };
    let get_capacity: GetCapacity = unsafe { std::mem::transmute(capacity_ptr) };
    if unsafe { get_capacity(env, buffer) } < (count * 8) as i64 {
        return -1;
    }
    if count == 0 {
        return 0;
    }
    let address = unsafe { get_address(env, buffer) };
    if address.is_null() {
        return -1;
    }
    let bytes = unsafe { std::slice::from_raw_parts_mut(address.cast::<u8>(), count * 8) };
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        SORT_SCRATCH.with(|cell| {
            let mut pair = cell.borrow_mut();
            let (data, scratch) = &mut *pair;
            data.resize(count, 0);
            scratch.resize(count, 0);
            for (i, value) in data.iter_mut().enumerate() {
                let raw = u32::from_le_bytes(bytes[i * 4..i * 4 + 4].try_into().unwrap());
                *value = (descending_float_key(raw) as u64) << 32 | i as u64;
            }
            radix_sort(data, scratch);
            for (i, &value) in data.iter().enumerate() {
                bytes[count * 4 + i * 4..count * 4 + i * 4 + 4]
                    .copy_from_slice(&(value as u32).to_le_bytes());
            }
        });
        0
    }))
    .unwrap_or(-1)
}

const ALL: u64 = (1_u64 << 36) - 1;

#[cfg(test)]
#[inline]
fn mark(bits: &mut [u64; 64], p: usize) {
    bits[p >> 6] |= 1_u64 << (p & 63);
}
#[cfg(test)]
#[inline]
fn marked(bits: &[u64; 64], p: usize) -> bool {
    bits[p >> 6] & (1_u64 << (p & 63)) != 0
}
/// Exact row-span connectivity. Fixed 1536-byte array payload; no heap allocation.
/// This source requires cargo test/build; source presence is NOT native availability.
pub fn resolve(opaque: &[u64; 64]) -> u64 {
    let count: u32 = opaque.iter().map(|word| word.count_ones()).sum();
    if count < 256 {
        return ALL;
    }
    if count == 4096 {
        return 0;
    }
    let mut free = [0_u16; 256];
    let mut pending = [0_u16; 256];
    let mut queue = [0_u16; 256];
    for i in 0..64 {
        let empty = !opaque[i];
        for j in 0..4 {
            free[4 * i + j] = (empty >> (16 * j)) as u16;
        }
    }
    let mut result = 0_u64;
    for start in 0..256_usize {
        let y = start >> 4;
        let z = start & 15;
        let boundary = if y == 0 || y == 15 || z == 0 || z == 15 {
            0xffff_u16
        } else {
            0x8001_u16
        };
        while free[start] & boundary != 0 {
            let candidates = free[start] & boundary;
            pending[start] = candidates & candidates.wrapping_neg();
            queue[0] = start as u16;
            let (mut head, mut tail, mut faces) = (0_usize, 1_usize, 0_u8);
            while head < tail {
                let row = queue[head & 255] as usize;
                head += 1;
                let available = free[row];
                let mut span = pending[row] & available;
                pending[row] = 0;
                if span == 0 {
                    continue;
                }
                loop {
                    let expanded = span | (((span << 1) | (span >> 1)) & available);
                    if expanded == span {
                        break;
                    }
                    span = expanded;
                }
                free[row] &= !span;
                let ry = row >> 4;
                let rz = row & 15;
                if ry == 0 {
                    faces |= 1;
                }
                if ry == 15 {
                    faces |= 2;
                }
                if rz == 0 {
                    faces |= 4;
                }
                if rz == 15 {
                    faces |= 8;
                }
                if span & 1 != 0 {
                    faces |= 16;
                }
                if span & 0x8000 != 0 {
                    faces |= 32;
                }
                if faces == 63 {
                    return ALL;
                }
                if ry > 0 {
                    push_row(&free, &mut pending, &mut queue, &mut tail, row - 16, span);
                }
                if ry < 15 {
                    push_row(&free, &mut pending, &mut queue, &mut tail, row + 16, span);
                }
                if rz > 0 {
                    push_row(&free, &mut pending, &mut queue, &mut tail, row - 1, span);
                }
                if rz < 15 {
                    push_row(&free, &mut pending, &mut queue, &mut tail, row + 1, span);
                }
                debug_assert!(tail - head <= 256);
            }
            for a in 0..6 {
                for b in 0..6 {
                    if faces & (1 << a) != 0 && faces & (1 << b) != 0 {
                        result |= 1_u64 << (a * 6 + b);
                    }
                }
            }
            if result == ALL {
                return ALL;
            }
        }
    }
    result
}
#[inline]
fn push_row(
    free: &[u16; 256],
    pending: &mut [u16; 256],
    queue: &mut [u16; 256],
    tail: &mut usize,
    row: usize,
    span: u16,
) {
    let next = free[row] & span;
    if next != 0 {
        if pending[row] == 0 {
            queue[*tail & 255] = row as u16;
            *tail += 1;
        }
        pending[row] |= next;
    }
}

/// Version of the deliberately small JNI ABI.
#[no_mangle]
pub extern "system" fn Java_fr_pierrecreeper_trinium_NativeBridge_apiVersion0(
    _env: *mut c_void,
    _class: *mut c_void,
) -> i64 {
    3
}

/// A native call per section, not per block. The JVM owns the direct buffer.
///
/// # Safety
/// `env` and `buffer` must be valid JNI references supplied by the JVM for this call.
/// Java always supplies a direct ByteBuffer of exactly 512 bytes. No pointer is retained.
#[no_mangle]
pub unsafe extern "system" fn Java_fr_pierrecreeper_trinium_NativeBridge_resolve0(
    env: *mut c_void,
    _class: *mut c_void,
    buffer: *mut c_void,
) -> i64 {
    if env.is_null() || buffer.is_null() {
        return -1;
    }
    // Oracle JNI specification: slots 230/231 are GetDirectBufferAddress/Capacity.
    // JNIEnv* is a pointer to a pointer to the interface table. Its entries are pointers.
    let table = unsafe { *(env as *const *const *const c_void) };
    if table.is_null() {
        return -1;
    }
    type GetAddress = unsafe extern "system" fn(*mut c_void, *mut c_void) -> *mut c_void;
    type GetCapacity = unsafe extern "system" fn(*mut c_void, *mut c_void) -> i64;
    let address_ptr = unsafe { *table.add(230) };
    let capacity_ptr = unsafe { *table.add(231) };
    if address_ptr.is_null() || capacity_ptr.is_null() {
        return -1;
    }
    let get_address: GetAddress = unsafe { std::mem::transmute(address_ptr) };
    let get_capacity: GetCapacity = unsafe { std::mem::transmute(capacity_ptr) };
    if unsafe { get_capacity(env, buffer) } != 512 {
        return -1;
    }
    let address = unsafe { get_address(env, buffer) };
    if address.is_null() {
        return -1;
    }
    // Reads use alignment-1 bytes and explicit LE conversion; no aligned-u64 assumption.
    let bytes = unsafe { std::slice::from_raw_parts(address.cast::<u8>(), 512) };
    std::panic::catch_unwind(|| {
        let mut opaque = [0_u64; 64];
        for (word, chunk) in opaque.iter_mut().zip(bytes.chunks_exact(8)) {
            let mut eight = [0_u8; 8];
            eight.copy_from_slice(chunk);
            *word = u64::from_le_bytes(eight);
        }
        resolve(&opaque) as i64
    })
    .unwrap_or(-1)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;
    #[test]
    fn sort_special_values_and_stability() {
        let values = [
            f32::NAN,
            -0.0,
            0.0,
            f32::INFINITY,
            f32::NEG_INFINITY,
            1.0,
            1.0,
            -1.0,
            f32::from_bits(0xffc0_1234),
            f32::from_bits(0x7f80_0001),
        ];
        let mut data: Vec<u64> = values
            .iter()
            .enumerate()
            .map(|(i, v)| (descending_float_key(v.to_bits()) as u64) << 32 | i as u64)
            .collect();
        radix_sort(&mut data, &mut vec![0; values.len()]);
        assert_eq!(
            data.iter().map(|v| *v as u32).collect::<Vec<_>>(),
            [0, 8, 9, 3, 5, 6, 2, 1, 7, 4]
        );
    }
    #[test]
    fn radix_matches_comparison_sort() {
        let mut seed = 4321_u64;
        for size in [0, 1, 2, 127, 128, 255, 256, 1023, 4096, 65536] {
            let mut values = Vec::new();
            for i in 0..size {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                values.push((descending_float_key(seed as u32) as u64) << 32 | i as u64);
            }
            let mut reference = values.clone();
            reference.sort();
            radix_sort(&mut values, &mut vec![0; size]);
            assert_eq!(values, reference);
        }
    }
    // Independent per-voxel reference, deliberately different from the row-span kernel.
    fn reference(opaque: &[u64; 64]) -> u64 {
        let count: u32 = opaque.iter().map(|w| w.count_ones()).sum();
        if count < 256 {
            return ALL;
        }
        let mut seen = [false; 4096];
        let mut result = 0_u64;
        let dirs = [
            (0_i32, -1_i32, 0_i32),
            (0, 1, 0),
            (0, 0, -1),
            (0, 0, 1),
            (-1, 0, 0),
            (1, 0, 0),
        ];
        for start in 0..4096_usize {
            let (x, z, y) = (start % 16, start / 16 % 16, start / 256);
            if x != 0 && x != 15 && y != 0 && y != 15 && z != 0 && z != 15 {
                continue;
            }
            if seen[start] || marked(opaque, start) {
                continue;
            }
            let mut queue = VecDeque::new();
            let mut faces = [false; 6];
            queue.push_back(start);
            seen[start] = true;
            while let Some(p) = queue.pop_front() {
                let (x, z, y) = ((p % 16) as i32, (p / 16 % 16) as i32, (p / 256) as i32);
                if y == 0 {
                    faces[0] = true;
                }
                if y == 15 {
                    faces[1] = true;
                }
                if z == 0 {
                    faces[2] = true;
                }
                if z == 15 {
                    faces[3] = true;
                }
                if x == 0 {
                    faces[4] = true;
                }
                if x == 15 {
                    faces[5] = true;
                }
                for (dx, dy, dz) in dirs {
                    let (nx, ny, nz) = (x + dx, y + dy, z + dz);
                    if !(0..16).contains(&nx) || !(0..16).contains(&ny) || !(0..16).contains(&nz) {
                        continue;
                    }
                    let neighbor = (nx + nz * 16 + ny * 256) as usize;
                    if seen[neighbor] || marked(opaque, neighbor) {
                        continue;
                    }
                    seen[neighbor] = true;
                    queue.push_back(neighbor);
                }
            }
            for a in 0..6 {
                for b in 0..6 {
                    if faces[a] && faces[b] {
                        result |= 1_u64 << (a * 6 + b);
                    }
                }
            }
        }
        result
    }
    #[test]
    fn empty_and_solid() {
        assert_eq!(resolve(&[0; 64]), ALL);
        assert_eq!(resolve(&[u64::MAX; 64]), 0);
    }
    #[test]
    fn walls_and_tunnels() {
        for axis in 0..3 {
            let mut wall = [0_u64; 64];
            let mut tunnel = [u64::MAX; 64];
            for y in 0..16 {
                for z in 0..16 {
                    for x in 0..16 {
                        let coords = [x, y, z];
                        let p = x + 16 * z + 256 * y;
                        if coords[axis] == 7 {
                            mark(&mut wall, p);
                        }
                        if coords[(axis + 1) % 3] == 8 && coords[(axis + 2) % 3] == 8 {
                            tunnel[p >> 6] &= !(1_u64 << (p & 63));
                        }
                    }
                }
            }
            assert_eq!(resolve(&wall), reference(&wall));
            assert_eq!(resolve(&tunnel), reference(&tunnel));
        }
    }
    #[test]
    fn random_parity_and_immutability() {
        let mut seed = 0x5452494e49554d_u64;
        for case in 0..2048_usize {
            let mut input = [0_u64; 64];
            let threshold = [2, 8, 20, 40, 60, 80, 95, 99][case % 8];
            for p in 0..4096 {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                if seed % 100 < threshold {
                    mark(&mut input, p);
                }
            }
            let before = input;
            assert_eq!(resolve(&input), reference(&input), "fixture {case}");
            assert_eq!(input, before);
        }
    }
}
