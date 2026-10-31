//! Independent machine-ABI and transport tests: real typed APIENTRY callbacks replace
//! the GL driver. No OpenGL context is needed, and no test callback dereferences a pointer.
//! The ignored benchmark measures CPU submission only, never GPU time or game FPS.
use super::*;
use std::cell::RefCell;
use std::hint::black_box;
use std::time::Instant;

#[derive(Debug, PartialEq, Eq)]
struct Call {
    opcode: usize,
    arguments: Vec<u64>,
}

thread_local! {
    static CALLS: RefCell<Vec<Call>> = const { RefCell::new(Vec::new()) };
}

trait WireValue {
    fn bits(self) -> u64;
}
impl WireValue for u32 {
    fn bits(self) -> u64 {
        self as u64
    }
}
impl WireValue for u8 {
    fn bits(self) -> u64 {
        self as u64
    }
}
impl WireValue for usize {
    fn bits(self) -> u64 {
        self as u64
    }
}
impl WireValue for f32 {
    fn bits(self) -> u64 {
        self.to_bits() as u64
    }
}

macro_rules! callbacks {
    ($(($capture:ident, $bench:ident, $op:literal, ($($arg:ident: $kind:ty),*))),* $(,)?) => {
        $(
            unsafe extern "system" fn $capture($($arg: $kind),*) {
                CALLS.with(|calls| calls.borrow_mut().push(Call {
                    opcode: $op,
                    arguments: vec![$($arg.bits()),*],
                }));
            }
            #[inline(never)]
            unsafe extern "system" fn $bench($($arg: $kind),*) {
                black_box(($op, $($arg),*));
            }
        )*
        fn function_words(benchmark: bool) -> Vec<u64> {
            let addresses = if benchmark {
                [$($bench as *const () as usize),*]
            } else {
                [$($capture as *const () as usize),*]
            };
            assert_eq!(addresses.len(), FUNCTION_COUNT);
            addresses.into_iter().map(|p| (p as u64).to_le()).collect()
        }
    }
}

// OpenGL scalar widths: GLenum/GLuint/GLint/GLsizei = 32 bits,
// GLboolean = 8 bits, GLfloat = 32 bits, pointers/GLintptr = pointer width.
// Signed GL integers deliberately use their identical-width wire bit pattern.
callbacks! {
    (c00,b00,0,(a:u32)),
    (c01,b01,1,(a:u32)),
    (c02,b02,2,(a:u32,b:u32)),
    (c03,b03,3,(a:u32)),
    (c04,b04,4,(a:u32)),
    (c05,b05,5,(a:u32,b:u32)),
    (c06,b06,6,(a:u32,b:u32)),
    (c07,b07,7,(a:u32)),
    (c08,b08,8,(a:u8)),
    (c09,b09,9,(a:f32,b:f32)),
    (c10,b10,10,(a:u32,b:u8,c:u8,d:u8,e:u8)),
    (c11,b11,11,(a:u32,b:u32,c:u32,d:u32)),
    (c12,b12,12,(a:u32,b:u32)),
    (c13,b13,13,(a:u32,b:u32)),
    (c14,b14,14,(a:u32)),
    (c15,b15,15,(a:u32,b:u32)),
    (c16,b16,16,(a:u32,b:u32,c:u32)),
    (c17,b17,17,(a:u32,b:u32,c:u32,d:u32)),
    (c18,b18,18,(a:u32,b:u32,c:u32,d:usize,e:usize)),
    (c19,b19,19,(a:u32,b:u32,c:u32)),
    (c20,b20,20,(a:u32,b:u32)),
    (c21,b21,21,(a:u32,b:u32,c:usize,d:u32)),
    (c22,b22,22,(a:u32,b:u32,c:u32,d:u32,e:usize)),
    (c23,b23,23,(a:u32,b:u32,c:u32,d:u8,e:u32,f:usize)),
    (c24,b24,24,(a:u32,b:u32)),
    (c25,b25,25,(a:u32,b:u32,c:u32,d:usize,e:u32,f:u32,g:u32)),
    (c26,b26,26,(a:u32,b:u32,c:u32,d:usize,e:u32,f:u32)),
    (c27,b27,27,(a:u32,b:u32,c:u32,d:u32,e:u32)),
    (c28,b28,28,(a:u32,b:u32,c:u32,d:u32)),
    (c29,b29,29,(a:u32,b:usize,c:usize,d:u32)),
    (c30,b30,30,(a:u32,b:usize,c:u32,d:usize,e:u32,f:usize)),
    (c31,b31,31,(a:u32,b:usize,c:u32,d:u32)),
    (c32,b32,32,(a:u32,b:usize)),
    (c33,b33,33,(a:u32,b:u32,c:usize,d:u32,e:u32)),
    (c34,b34,34,(a:u32,b:u32,c:usize)),
}

// Independent expected narrowing rules; unused record words must have no effect.
const ARGUMENT_KINDS: [&str; 35] = [
    "I", "I", "II", "I", "I", "II", "II", "I", "B", "FF", "IBBBB", "IIII", "II", "II", "I", "II",
    "III", "IIII", "IIIPP", "III", "II", "IIPI", "IIIIP", "IIIBIP", "II", "IIIPIII", "IIIPII",
    "IIIII", "IIII", "IPPI", "IPIPIP", "IPII", "IP", "IIPII", "IIP",
];

fn bytes(words: &[u64]) -> &[u8] {
    // SAFETY: viewing initialized integer storage as bytes preserves its lifetime.
    unsafe { std::slice::from_raw_parts(words.as_ptr().cast(), words.len() * 8) }
}

fn bytes_mut(words: &mut [u64]) -> &mut [u8] {
    unsafe { std::slice::from_raw_parts_mut(words.as_mut_ptr().cast(), words.len() * 8) }
}

fn packet_words(ops: &[usize], seed: u64) -> Vec<u64> {
    let mut state = seed;
    ops.iter()
        .flat_map(|&op| {
            let mut row = [0_u64; 8];
            row[0] = (op as u64).to_le();
            for arg in &mut row[1..] {
                state = state
                    .wrapping_mul(6364136223846793005)
                    .wrapping_add(1442695040888963407);
                *arg = state.to_le();
            }
            row
        })
        .collect()
}

fn expected(ops: &[usize], packet: &[u8]) -> Vec<Call> {
    ops.iter()
        .enumerate()
        .map(|(i, &opcode)| Call {
            opcode,
            arguments: ARGUMENT_KINDS[opcode]
                .bytes()
                .enumerate()
                .map(|(j, kind)| {
                    let value = word(packet, i * 64 + 8 + j * 8);
                    match kind {
                        b'B' => (value as u8) as u64,
                        b'P' => (value as usize) as u64,
                        b'I' | b'F' => (value as u32) as u64,
                        _ => unreachable!(),
                    }
                })
                .collect(),
        })
        .collect()
}

fn take_calls() -> Vec<Call> {
    CALLS.with(|calls| std::mem::take(&mut *calls.borrow_mut()))
}

#[test]
fn all_35_opcodes_preserve_abi_order_bits_and_every_alignment() {
    let functions = function_words(false);
    // Reverse and repeat the API sequence so dispatch order cannot pass accidentally.
    let ops: Vec<_> = (0..FUNCTION_COUNT)
        .chain((0..FUNCTION_COUNT).rev())
        .collect();
    for seed in [0, 1, u64::MAX, 0x9e3779b97f4a7c15] {
        let packet = packet_words(&ops, seed);
        let want = expected(&ops, bytes(&packet));
        for offset in 0..8 {
            let mut backing = vec![0_u64; packet.len() + 1];
            bytes_mut(&mut backing)[offset..offset + packet.len() * 8]
                .copy_from_slice(bytes(&packet));
            let data = &bytes(&backing)[offset..offset + packet.len() * 8];
            assert_eq!(unsafe { execute(bytes(&functions), data, ops.len()) }, 0);
            assert_eq!(take_calls(), want, "offset={offset}, seed={seed}");
        }
    }
}

#[test]
fn float_bits_survive_nan_infinity_signed_zero_and_subnormal_transport() {
    let functions = function_words(false);
    let bits = [
        0_u32,
        0x8000_0000,
        0x7f80_0000,
        0xff80_0000,
        0x7fc0_4321,
        0xffc0_5678,
        0x7f80_0001,
        1,
        0x8000_0001,
        0x3f80_0000,
    ];
    for a in bits {
        for b in bits {
            let mut packet = packet_words(&[9], 7);
            packet[1] = (0xaabb_ccdd_0000_0000 | a as u64).to_le();
            packet[2] = (0x8877_6655_0000_0000 | b as u64).to_le();
            assert_eq!(unsafe { execute(bytes(&functions), bytes(&packet), 1) }, 0);
            assert_eq!(
                take_calls(),
                vec![Call {
                    opcode: 9,
                    arguments: vec![a as u64, b as u64]
                }]
            );
        }
    }
}

#[test]
fn invalid_last_record_rejects_batch_before_any_gl_callback() {
    let functions = function_words(false);
    let mut packet = packet_words(&[0, 25, 34], 123);
    packet[16] = (FUNCTION_COUNT as u64).to_le();
    assert_eq!(unsafe { execute(bytes(&functions), bytes(&packet), 3) }, -1);
    assert!(take_calls().is_empty());
    packet[16] = u64::MAX.to_le();
    assert_eq!(unsafe { execute(bytes(&functions), bytes(&packet), 3) }, -1);
    assert!(take_calls().is_empty());
    packet[16] = 34_u64.to_le();
    let mut missing = functions.clone();
    missing[34] = 0;
    assert_eq!(unsafe { execute(bytes(&missing), bytes(&packet), 3) }, -1);
    assert!(take_calls().is_empty());
    for count in [4, MAX_COMMANDS + 1, usize::MAX] {
        assert_eq!(
            unsafe { execute(bytes(&functions), bytes(&packet), count) },
            -1
        );
        assert!(take_calls().is_empty());
    }
    assert_eq!(
        unsafe {
            execute(
                &bytes(&functions)[..FUNCTION_COUNT * 8 - 1],
                bytes(&packet),
                3,
            )
        },
        -1
    );
    assert_eq!(
        unsafe { execute(bytes(&functions), &bytes(&packet)[..191], 3) },
        -1
    );
    assert!(take_calls().is_empty());
}

#[test]
fn upper_boundary_and_empty_batch_remain_supported() {
    let functions = function_words(false);
    let ops: Vec<_> = (0..MAX_COMMANDS).map(|i| i % FUNCTION_COUNT).collect();
    let packet = packet_words(&ops, 73);
    assert_eq!(
        unsafe { execute(bytes(&functions), bytes(&packet), MAX_COMMANDS) },
        0
    );
    assert_eq!(take_calls(), expected(&ops, bytes(&packet)));
    assert_eq!(unsafe { execute(bytes(&functions), &[], 0) }, 0);
    assert!(take_calls().is_empty());
}

struct MockBuffer {
    address: *mut c_void,
    capacity: i64,
}
unsafe extern "system" fn mock_address(_: *mut c_void, object: *mut c_void) -> *mut c_void {
    unsafe { (*(object.cast::<MockBuffer>())).address }
}
unsafe extern "system" fn mock_capacity(_: *mut c_void, object: *mut c_void) -> i64 {
    unsafe { (*(object.cast::<MockBuffer>())).capacity }
}
fn mock_buffer(data: &[u8]) -> MockBuffer {
    MockBuffer {
        address: data.as_ptr().cast_mut().cast(),
        capacity: data.len() as i64,
    }
}
fn object(buffer: &mut MockBuffer) -> *mut c_void {
    (buffer as *mut MockBuffer).cast()
}
fn table() -> [*const c_void; 232] {
    let mut table = [std::ptr::null(); 232];
    table[230] = mock_address as *const () as *const c_void;
    table[231] = mock_capacity as *const () as *const c_void;
    table
}

#[test]
fn legacy_and_packed_jni_entrypoints_have_identical_typed_effects() {
    let table = table();
    let mut table_address = table.as_ptr();
    let env = (&mut table_address as *mut *const *const c_void).cast();
    let functions = function_words(false);
    let ops: Vec<_> = (0..FUNCTION_COUNT).collect();
    let packet = packet_words(&ops, 153);
    let mut f = mock_buffer(bytes(&functions));
    let mut p = mock_buffer(bytes(&packet));
    assert_eq!(
        unsafe {
            Java_fr_pierrecreeper_trinium_graphics_RustGlDriver_execute0(
                env,
                std::ptr::null_mut(),
                object(&mut f),
                object(&mut p),
                ops.len() as i32,
            )
        },
        0
    );
    let legacy = take_calls();
    assert_eq!(legacy, expected(&ops, bytes(&packet)));
    for offset in 0..8 {
        let length = FUNCTION_BYTES + packet.len() * 8;
        let mut combined = vec![0_u64; length.div_ceil(8) + 1];
        let data = &mut bytes_mut(&mut combined)[offset..offset + length];
        data[..functions.len() * 8].copy_from_slice(bytes(&functions));
        data[functions.len() * 8..FUNCTION_BYTES].fill(0xff);
        data[FUNCTION_BYTES..].copy_from_slice(bytes(&packet));
        let mut packed = mock_buffer(data);
        assert_eq!(
            unsafe {
                Java_fr_pierrecreeper_trinium_graphics_RustGlDriver_executePacked0(
                    env,
                    std::ptr::null_mut(),
                    object(&mut packed),
                    ops.len() as i32,
                )
            },
            0
        );
        assert_eq!(take_calls(), legacy, "JNI packed alignment {offset}");
    }
}

#[test]
fn jni_rejects_null_services_invalid_capacity_and_bad_counts_before_gl() {
    let mut table = table();
    let mut table_address = table.as_ptr();
    let env = (&mut table_address as *mut *const *const c_void).cast();
    let functions = function_words(false);
    let packet = packet_words(&[0], 91);
    let mut f = mock_buffer(bytes(&functions));
    let mut p = mock_buffer(bytes(&packet));
    let null = std::ptr::null_mut();
    let invoke = |env, f: &mut MockBuffer, p: &mut MockBuffer, count| unsafe {
        Java_fr_pierrecreeper_trinium_graphics_RustGlDriver_execute0(
            env,
            null,
            object(f),
            object(p),
            count,
        )
    };
    assert_eq!(invoke(null, &mut f, &mut p, 1), -1);
    assert_eq!(
        unsafe {
            Java_fr_pierrecreeper_trinium_graphics_RustGlDriver_execute0(
                env,
                null,
                null,
                object(&mut p),
                1,
            )
        },
        -1
    );
    for count in [-1, i32::MIN, MAX_COMMANDS as i32 + 1, i32::MAX] {
        assert_eq!(invoke(env, &mut f, &mut p, count), -1);
    }
    for capacity in [-1, i64::MIN, 0, 63] {
        p.capacity = capacity;
        assert_eq!(invoke(env, &mut f, &mut p, 1), -1);
    }
    p.capacity = 64;
    p.address = null;
    assert_eq!(invoke(env, &mut f, &mut p, 1), -1);
    p = mock_buffer(bytes(&packet));
    for slot in [230, 231] {
        let saved = table[slot];
        table[slot] = std::ptr::null();
        assert_eq!(invoke(env, &mut f, &mut p, 1), -1);
        table[slot] = saved;
    }
    table_address = std::ptr::null();
    // Keep the updated table address observable through the synthetic JNI environment.
    black_box(table_address);
    assert_eq!(invoke(env, &mut f, &mut p, 1), -1);
    assert!(take_calls().is_empty());
}

// Frozen pre-optimization executor for A/B measurements and differential checks.
unsafe fn legacy_execute(functions: &[u8], packet: &[u8], count: usize) -> i32 {
    if !validate(functions, packet, count) {
        return -1;
    }
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

#[test]
fn randomized_sequences_match_the_previous_executor_exactly() {
    let functions = function_words(false);
    let mut seed = 0x12345678abcdef01_u64;
    for case in 0..256 {
        let count = case % 129;
        let ops: Vec<_> = (0..count)
            .map(|_| {
                seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
                (seed >> 32) as usize % FUNCTION_COUNT
            })
            .collect();
        let packet = packet_words(&ops, seed);
        assert_eq!(
            unsafe { legacy_execute(bytes(&functions), bytes(&packet), count) },
            0
        );
        let legacy = take_calls();
        assert_eq!(
            unsafe { execute(bytes(&functions), bytes(&packet), count) },
            0
        );
        assert_eq!(take_calls(), legacy, "case {case}");
    }
}

#[test]
#[ignore = "CPU submission microbenchmark; run alone in release after game measurements"]
fn native_submission_decoder_abba() {
    type Executor = unsafe fn(&[u8], &[u8], usize) -> i32;
    let functions = function_words(true);
    let batches: usize = std::env::var("TRINIUM_NATIVE_BENCH_BATCHES")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(100_000);
    let repeats: usize = std::env::var("TRINIUM_NATIVE_BENCH_REPEATS")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(7);
    // Mixed is a representative state/buffer/indexed-draw pattern, not a claim
    // about measured Minecraft frequencies. All-opcodes exercises the complete ABI.
    let all_ops: Vec<_> = (0..FUNCTION_COUNT).collect();
    let patterns: [(&str, &[usize]); 3] = [
        ("mixed", &[0, 1, 2, 18, 21, 20, 14, 15, 25, 18, 21, 26]),
        (
            "state",
            &[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17],
        ),
        ("all-opcodes", &all_ops),
    ];
    println!("decoder_bench,cpu_submission_only=true,batches={batches},repeats={repeats},pointer_bits={}", usize::BITS);
    for (pattern_name, pattern) in patterns {
        for count in [1, 4, 16, 64, 128] {
            // Several distinct records prevent constant-input specialization/cache tricks.
            let packets: Vec<_> = (0..16)
                .map(|seed| {
                    let ops: Vec<_> = (0..count)
                        .map(|i| pattern[(i + seed) % pattern.len()])
                        .collect();
                    packet_words(&ops, seed as u64 + 1)
                })
                .collect();
            let time = |executor: Executor, iterations: usize| {
                let executor = black_box(executor);
                let start = Instant::now();
                for batch in 0..iterations {
                    let packet = bytes(&packets[batch & 15]);
                    let result = unsafe {
                        executor(
                            black_box(bytes(&functions)),
                            black_box(packet),
                            black_box(count),
                        )
                    };
                    black_box(result);
                }
                start.elapsed().as_nanos() as f64 / (iterations * count) as f64
            };
            time(legacy_execute, 5_000);
            time(execute, 5_000);
            for repeat in 0..repeats {
                let a1 = time(legacy_execute, batches);
                let b1 = time(execute, batches);
                let b2 = time(execute, batches);
                let a2 = time(legacy_execute, batches);
                println!("decoder_sample,pattern={pattern_name},count={count},repeat={repeat},old_a1_ns={a1:.6},new_b1_ns={b1:.6},new_b2_ns={b2:.6},old_a2_ns={a2:.6}");
            }
        }
    }
}
