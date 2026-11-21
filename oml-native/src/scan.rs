//! The memory-mapped mod-jar scanner (`oml_scan_jar_v1`).
//!
//! Behavior contract:
//!
//! * the jar is mapped read-only and the ZIP central directory is parsed in place (ZIP64 included);
//! * `STORED` entries are scanned in the mapping, `DEFLATED` entries go through libdeflate;
//! * only the constant pool is scanned, using one common-prefix search;
//! * MR-JAR applies only when the manifest declares `Multi-Release: true`; the highest version
//!   not above the caller's runtime wins and shadows the base copy;
//! * `@Mod` records carry their class bytes, so the Java side does not reopen the jar;
//! * every read is checked, and any unsupported/corrupt shape returns a negative code so the
//!   Java walk takes over.

use libdeflater::{DecompressionError, Decompressor};
use memmap2::Mmap;
use std::cell::RefCell;
use std::collections::HashMap;
use std::fs::File;
use std::os::raw::c_int;
use std::path::Path;
use std::slice;

const SCAN_MOD: u8 = 1;
const SCAN_RULE: u8 = 2;
const SCAN_HOOK: u8 = 4;
const SCAN_RESERVED: u8 = 8;

const RECORD_HEADER: usize = 5;
const CP_PROBE: usize = 16 * 1024;

const END_SIG: u32 = 0x0605_4b50;
const END64_SIG: u32 = 0x0606_4b50;
const LOCATOR64_SIG: u32 = 0x0706_4b50;
const CD_SIG: u32 = 0x0201_4b50;
const LOCAL_SIG: u32 = 0x0403_4b50;

const VERSION_PREFIX: &[u8] = b"META-INF/versions/";
const RESERVED_PREFIX: &[u8] = b"org/ohmyloader/";
const MANIFEST_NAME: &[u8] = b"META-INF/MANIFEST.MF";
const MARKER_PREFIX: &[u8] = b"Lorg/ohmyloader/";
const MOD_TAIL: &[u8] = b"api/core/Mod;";
const RULE_TAIL: &[u8] = b"api/core/inject/RuleSource;";
const HOOKED_TAIL: &[u8] = b"api/core/hook/Hooked;";
const MERGED_TAIL: &[u8] = b"api/core/hook/Merged;";

thread_local! {
    static CLASS_BUF: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
    static DECOMPRESSOR: RefCell<Option<Decompressor>> = const { RefCell::new(None) };
}

fn slice(data: &[u8], offset: usize, len: usize) -> Option<&[u8]> {
    data.get(offset..offset.checked_add(len)?)
}

fn u16le(data: &[u8], offset: usize) -> Option<u16> {
    let bytes = slice(data, offset, 2)?;
    Some(u16::from_le_bytes([bytes[0], bytes[1]]))
}

/// Class files (unlike ZIP structures) are big-endian.
fn u16be(data: &[u8], offset: usize) -> Option<u16> {
    let bytes = slice(data, offset, 2)?;
    Some(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn u32le(data: &[u8], offset: usize) -> Option<u32> {
    let bytes = slice(data, offset, 4)?;
    Some(u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

fn u64le(data: &[u8], offset: usize) -> Option<u64> {
    let bytes = slice(data, offset, 8)?;
    Some(u64::from_le_bytes([
        bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
    ]))
}

struct EndRecord {
    count: u64,
    cd_offset: u64,
    cd_size: u64,
}

fn find_end_record(data: &[u8]) -> Option<EndRecord> {
    if data.len() < 22 {
        return None;
    }
    let mut pos = data.len() - 22;
    let floor = pos.saturating_sub(0xFFFF);
    loop {
        if u32le(data, pos) == Some(END_SIG) {
            let comment_len = u16le(data, pos + 20).unwrap_or(0) as usize;
            if pos.saturating_add(22).saturating_add(comment_len) >= data.len() {
                let count16 = u16le(data, pos + 10).unwrap_or(0);
                let cd_size32 = u32le(data, pos + 12).unwrap_or(0);
                let cd_off32 = u32le(data, pos + 16).unwrap_or(0);
                if count16 == 0xFFFF || cd_size32 == 0xFFFF_FFFF || cd_off32 == 0xFFFF_FFFF {
                    return find_end_record64(data, pos);
                }
                let data_len = data.len() as u64;
                let cd_off = cd_off32 as u64;
                let cd_size = cd_size32 as u64;
                if cd_off <= data_len && cd_size <= data_len - cd_off {
                    return Some(EndRecord {
                        count: count16 as u64,
                        cd_offset: cd_off,
                        cd_size,
                    });
                }
            }
        }
        if pos == floor {
            break;
        }
        pos -= 1;
    }
    None
}

fn find_end_record64(data: &[u8], end_pos: usize) -> Option<EndRecord> {
    if end_pos < 20 {
        return None;
    }
    let locator = end_pos - 20;
    if u32le(data, locator) != Some(LOCATOR64_SIG) {
        return None;
    }
    let record_offset = u64le(data, locator + 8)?;
    let data_len = data.len() as u64;
    if record_offset > data_len || record_offset.saturating_add(56) > data_len {
        return None;
    }
    let pos = record_offset as usize;
    if u32le(data, pos) != Some(END64_SIG) {
        return None;
    }
    let count = u64le(data, pos + 32)?;
    let cd_size = u64le(data, pos + 40)?;
    let cd_off = u64le(data, pos + 48)?;
    if cd_off > data_len || cd_size > data_len - cd_off {
        return None;
    }
    Some(EndRecord {
        count,
        cd_offset: cd_off,
        cd_size,
    })
}

struct CdEntry<'a> {
    name: &'a [u8],
    method: u16,
    compressed: u64,
    uncompressed: u64,
    local_offset: u64,
    next: usize,
}

fn read_cd_entry(data: &[u8], off: usize) -> Option<CdEntry<'_>> {
    if off.checked_add(46)? > data.len() {
        return None;
    }
    if u32le(data, off)? != CD_SIG {
        return None;
    }
    let flags = u16le(data, off + 8)?;
    if flags & 1 != 0 {
        // Encrypted entries are unsupported; let the Java path decide what to do with the jar.
        return None;
    }
    let method = u16le(data, off + 10)?;
    let compressed32 = u32le(data, off + 20)?;
    let uncompressed32 = u32le(data, off + 24)?;
    let name_len = u16le(data, off + 28)? as usize;
    let extra_len = u16le(data, off + 30)? as usize;
    let comment_len = u16le(data, off + 32)? as usize;
    let local32 = u32le(data, off + 42)?;

    let name_start = off.checked_add(46)?;
    let extra_start = name_start.checked_add(name_len)?;
    let next = extra_start
        .checked_add(extra_len)?
        .checked_add(comment_len)?;
    if next > data.len() {
        return None;
    }
    let name = data.get(name_start..extra_start)?;

    let mut compressed = compressed32 as u64;
    let mut uncompressed = uncompressed32 as u64;
    let mut local = local32 as u64;
    if compressed32 == 0xFFFF_FFFF || uncompressed32 == 0xFFFF_FFFF || local32 == 0xFFFF_FFFF {
        let extra_end = extra_start.checked_add(extra_len)?;
        let mut p = extra_start;
        let mut found = false;
        while p.checked_add(4)? <= extra_end {
            let id = u16le(data, p)?;
            let size = u16le(data, p + 2)? as usize;
            p = p.checked_add(4)?;
            let block_end = p.checked_add(size)?;
            if block_end > extra_end {
                return None;
            }
            if id == 1 {
                found = true;
                let mut q = p;
                if uncompressed32 == 0xFFFF_FFFF {
                    if q.checked_add(8)? > block_end {
                        return None;
                    }
                    uncompressed = u64le(data, q)?;
                    q += 8;
                }
                if compressed32 == 0xFFFF_FFFF {
                    if q.checked_add(8)? > block_end {
                        return None;
                    }
                    compressed = u64le(data, q)?;
                    q += 8;
                }
                if local32 == 0xFFFF_FFFF {
                    if q.checked_add(8)? > block_end {
                        return None;
                    }
                    local = u64le(data, q)?;
                }
            }
            p = block_end;
        }
        if !found {
            return None;
        }
    }
    Some(CdEntry {
        name,
        method,
        compressed,
        uncompressed,
        local_offset: local,
        next,
    })
}

fn inflate(buf: &mut Vec<u8>, compressed: &[u8], uncompressed: usize) -> Option<usize> {
    if uncompressed == 0 {
        buf.clear();
        return Some(0);
    }
    if compressed.is_empty() {
        return None;
    }
    DECOMPRESSOR.with(|cell| {
        let mut slot = cell.borrow_mut();
        let decompressor = slot.get_or_insert_with(Decompressor::new);
        let probe = uncompressed.min(CP_PROBE);
        buf.resize(probe, 0);
        match decompressor.deflate_decompress(compressed, &mut buf[..probe]) {
            Ok(actual) => {
                buf.truncate(actual);
                Some(actual)
            }
            Err(DecompressionError::InsufficientSpace) if probe < uncompressed => {
                buf.resize(uncompressed, 0);
                match decompressor.deflate_decompress(compressed, &mut buf[..uncompressed]) {
                    Ok(actual) => {
                        buf.truncate(actual);
                        Some(actual)
                    }
                    Err(_) => None,
                }
            }
            Err(_) => None,
        }
    })
}

fn with_entry_bytes<R>(
    data: &[u8],
    entry: &CdEntry<'_>,
    buf: &mut Vec<u8>,
    f: impl FnOnce(&[u8]) -> R,
) -> Option<R> {
    let local = usize::try_from(entry.local_offset).ok()?;
    if u32le(data, local)? != LOCAL_SIG {
        return None;
    }
    let name_len = u16le(data, local + 26)? as usize;
    let extra_len = u16le(data, local + 28)? as usize;
    let start = local
        .checked_add(30)?
        .checked_add(name_len)?
        .checked_add(extra_len)?;
    let compressed_len = usize::try_from(entry.compressed).ok()?;
    let compressed = slice(data, start, compressed_len)?;
    match entry.method {
        0 => {
            let uncompressed = usize::try_from(entry.uncompressed).ok()?;
            Some(f(slice(compressed, 0, uncompressed)?))
        }
        8 => {
            let uncompressed = usize::try_from(entry.uncompressed).ok()?;
            let actual = inflate(buf, compressed, uncompressed)?;
            Some(f(&buf[..actual]))
        }
        _ => None,
    }
}

struct Versioned<'a> {
    logical: &'a [u8],
    version: u16,
}

/// Parses `META-INF/versions/N/<class>` without the runtime/feature gate.
fn parse_versioned(name: &[u8]) -> Option<Versioned<'_>> {
    let rest = name.strip_prefix(VERSION_PREFIX)?;
    let slash = rest.iter().position(|&byte| byte == b'/')?;
    if slash == 0 {
        return None;
    }
    let digits = &rest[..slash];
    if !digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let version: u16 = std::str::from_utf8(digits).ok()?.parse().ok()?;
    if version == 0 {
        return None;
    }
    let logical = &rest[slash + 1..];
    if logical.is_empty() || logical.starts_with(b"META-INF/") {
        return None;
    }
    Some(Versioned { logical, version })
}

fn versioned_name(name: &[u8], feature: u16) -> Option<Versioned<'_>> {
    let versioned = parse_versioned(name)?;
    (versioned.version <= feature).then_some(versioned)
}

fn manifest_is_multi_release(bytes: &[u8]) -> bool {
    for raw in bytes.split(|&byte| byte == b'\n') {
        let line = trim_ascii(raw);
        if line.len() >= 13 && line[..13].eq_ignore_ascii_case(b"Multi-Release") {
            let mut rest = trim_ascii_start(&line[13..]);
            if rest.first() == Some(&b':') {
                rest = trim_ascii_start(&rest[1..]);
                if rest.eq_ignore_ascii_case(b"true") {
                    return true;
                }
            }
        }
    }
    false
}

fn trim_ascii(bytes: &[u8]) -> &[u8] {
    let start = bytes
        .iter()
        .position(|byte| !matches!(byte, b' ' | b'\t' | b'\r'))
        .unwrap_or(bytes.len());
    let end = bytes
        .iter()
        .rposition(|byte| !matches!(byte, b' ' | b'\t' | b'\r'))
        .map(|position| position + 1)
        .unwrap_or(start);
    &bytes[start..end]
}

fn trim_ascii_start(bytes: &[u8]) -> &[u8] {
    let start = bytes
        .iter()
        .position(|byte| !matches!(byte, b' ' | b'\t'))
        .unwrap_or(bytes.len());
    &bytes[start..]
}

/// Standard MR-JAR only applies when the manifest declares `Multi-Release: true`.
fn find_multi_release(
    data: &[u8],
    cd_start: usize,
    cd_end: usize,
    count: u64,
    buf: &mut Vec<u8>,
) -> bool {
    let mut off = cd_start;
    let mut seen = 0u64;
    while seen < count {
        if off >= cd_end {
            return false;
        }
        let Some(entry) = read_cd_entry(data, off) else {
            return false;
        };
        if entry.next > cd_end {
            return false;
        }
        off = entry.next;
        seen += 1;
        if entry.name != MANIFEST_NAME {
            continue;
        }
        return with_entry_bytes(data, &entry, buf, manifest_is_multi_release).unwrap_or(false);
    }
    false
}

/// The constant-pool end offset, or None when the header is malformed or the pool overruns.
fn constant_pool_end(bytes: &[u8]) -> Option<usize> {
    if bytes.len() < 10 {
        return None;
    }
    if bytes.get(0..4)? != [0xCA, 0xFE, 0xBA, 0xBE] {
        return None;
    }
    let count = u16be(bytes, 8)?;
    if count == 0 {
        return None;
    }
    let mut offset = 10usize;
    let mut slot: u32 = 1;
    while slot < count as u32 {
        let tag = *bytes.get(offset)?;
        offset += 1;
        match tag {
            1 => {
                let len = u16be(bytes, offset)? as usize;
                offset = offset.checked_add(2)?.checked_add(len)?;
            }
            3 | 4 | 9 | 10 | 11 | 12 | 17 | 18 => offset = offset.checked_add(4)?,
            5 | 6 => {
                offset = offset.checked_add(8)?;
                slot += 1;
            }
            7 | 8 | 16 | 19 | 20 => offset = offset.checked_add(2)?,
            15 => offset = offset.checked_add(3)?,
            _ => return None,
        }
        if offset > bytes.len() {
            return None;
        }
        slot += 1;
    }
    Some(offset)
}

fn find(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    memchr::memmem::find(haystack, needle)
}

/// One common-prefix search over the constant pool; the four tails are only compared on a hit.
fn markers_in(bytes: &[u8]) -> u8 {
    let mut mask = 0u8;
    let mut offset = 0usize;
    while offset < bytes.len() {
        let Some(at) = find(&bytes[offset..], MARKER_PREFIX) else {
            break;
        };
        let tail_start = offset + at + MARKER_PREFIX.len();
        let tail = &bytes[tail_start.min(bytes.len())..];
        if tail.starts_with(MOD_TAIL) {
            mask |= SCAN_MOD;
        }
        if tail.starts_with(RULE_TAIL) {
            mask |= SCAN_RULE;
        }
        if tail.starts_with(HOOKED_TAIL) || tail.starts_with(MERGED_TAIL) {
            mask |= SCAN_HOOK;
        }
        offset = tail_start.max(offset + 1);
    }
    mask
}

fn scan_class(bytes: &[u8]) -> u8 {
    let end = constant_pool_end(bytes).unwrap_or(bytes.len());
    markers_in(&bytes[..end.min(bytes.len())])
}

struct Emitter<'a> {
    out: &'a mut [u8],
    used: usize,
    required: usize,
    overflow: bool,
}

impl Emitter<'_> {
    /// `@Mod` records append `[u32 len][bytes]` after the name.
    fn emit(&mut self, mask: u8, version: u16, name: &[u8], class_bytes: Option<&[u8]>) {
        if name.len() > u16::MAX as usize {
            return;
        }
        let payload = if mask & SCAN_MOD != 0 {
            class_bytes.map(|bytes| 4 + bytes.len()).unwrap_or(0)
        } else {
            0
        };
        let need = RECORD_HEADER + name.len() + payload;
        self.required = self.required.saturating_add(need);
        if self.overflow {
            return;
        }
        if need > self.out.len().saturating_sub(self.used) {
            self.overflow = true;
            return;
        }
        let at = self.used;
        self.out[at] = mask;
        self.out[at + 1..at + 3].copy_from_slice(&version.to_le_bytes());
        self.out[at + 3..at + 5].copy_from_slice(&(name.len() as u16).to_le_bytes());
        self.out[at + RECORD_HEADER..at + RECORD_HEADER + name.len()].copy_from_slice(name);
        if payload != 0 {
            let bytes = class_bytes.expect("payload implies class bytes");
            let p = at + RECORD_HEADER + name.len();
            self.out[p..p + 4].copy_from_slice(&(bytes.len() as u32).to_le_bytes());
            self.out[p + 4..p + 4 + bytes.len()].copy_from_slice(bytes);
        }
        self.used += need;
    }
}

fn scan_mapped(
    data: &[u8],
    requested_feature: u16,
    out: &mut [u8],
    counts: &mut [u64; 5],
) -> (c_int, u64) {
    let Some(end) = find_end_record(data) else {
        return (-6, 0);
    };
    let data_len = data.len() as u64;
    if end.cd_offset > data_len || end.cd_size > data_len - end.cd_offset {
        return (-6, 0);
    }
    let cd_start = end.cd_offset as usize;
    let cd_end = cd_start + end.cd_size as usize;
    if cd_end > data.len() {
        return (-6, 0);
    }

    let multi_release = CLASS_BUF.with(|cell| {
        let mut buf = cell.borrow_mut();
        find_multi_release(data, cd_start, cd_end, end.count, &mut buf)
    });
    let feature = if multi_release { requested_feature } else { 0 };

    // Pass 1: which logical classes have an MR-JAR copy, and which version wins.
    let mut winners: HashMap<&[u8], u16> = HashMap::new();
    let mut off = cd_start;
    let mut seen = 0u64;
    while seen < end.count {
        if off >= cd_end {
            return (-6, 0);
        }
        let Some(entry) = read_cd_entry(data, off) else {
            return (-6, 0);
        };
        if entry.next > cd_end {
            return (-6, 0);
        }
        off = entry.next;
        seen += 1;
        if !entry.name.ends_with(b".class") {
            continue;
        }
        let Some(versioned) = versioned_name(entry.name, feature) else {
            continue;
        };
        let Some(internal) = versioned.logical.strip_suffix(b".class") else {
            continue;
        };
        if internal.starts_with(RESERVED_PREFIX) {
            continue;
        }
        winners
            .entry(internal)
            .and_modify(|best| *best = (*best).max(versioned.version))
            .or_insert(versioned.version);
    }

    // Pass 2: emit winners. Base copies shadowed by an MR-JAR winner are skipped; reserved
    // entries are reported regardless (the Java side refuses the jar, but fixture jars can still
    // be scanned directly by the rule/hook front-ends).
    let mut emitter = Emitter {
        out,
        used: 0,
        required: 0,
        overflow: false,
    };
    let pass2 = CLASS_BUF.with(|cell| {
        let mut buf = cell.borrow_mut();
        let mut off = cd_start;
        let mut seen = 0u64;
        while seen < end.count {
            if off >= cd_end {
                return -6;
            }
            let Some(entry) = read_cd_entry(data, off) else {
                return -6;
            };
            if entry.next > cd_end {
                return -6;
            }
            off = entry.next;
            seen += 1;
            if !entry.name.ends_with(b".class") {
                continue;
            }

            let mut logical = entry.name;
            let mut version: u16 = 0;
            if entry.name.starts_with(b"META-INF/") {
                let Some(versioned) = parse_versioned(entry.name) else {
                    continue;
                };
                logical = versioned.logical;
                version = versioned.version;
            }
            let Some(internal) = logical.strip_suffix(b".class") else {
                continue;
            };
            if internal.is_empty() {
                continue;
            }

            if internal.starts_with(RESERVED_PREFIX) {
                if version == 0 || version <= feature {
                    let result = with_entry_bytes(data, &entry, &mut buf, |bytes| {
                        let mask = scan_class(bytes);
                        emitter.emit(
                            SCAN_RESERVED | mask,
                            version,
                            internal,
                            if mask & SCAN_MOD != 0 {
                                Some(bytes)
                            } else {
                                None
                            },
                        );
                    });
                    if result.is_none() {
                        return -8;
                    }
                } else {
                    emitter.emit(SCAN_RESERVED, version, internal, None);
                }
                counts[4] += 1;
                continue;
            }
            if version > feature {
                continue;
            }
            if version == 0 {
                if winners.contains_key(internal) {
                    continue;
                }
            } else if winners.get(internal) != Some(&version) {
                continue;
            }

            let result = with_entry_bytes(data, &entry, &mut buf, |bytes| {
                counts[3] += 1;
                let mask = scan_class(bytes);
                if mask != 0 {
                    emitter.emit(
                        mask,
                        version,
                        internal,
                        if mask & SCAN_MOD != 0 {
                            Some(bytes)
                        } else {
                            None
                        },
                    );
                    if mask & SCAN_MOD != 0 {
                        counts[0] += 1;
                    }
                    if mask & SCAN_RULE != 0 {
                        counts[1] += 1;
                    }
                    if mask & SCAN_HOOK != 0 {
                        counts[2] += 1;
                    }
                }
            });
            if result.is_none() {
                return -8;
            }
        }
        0
    });
    if pass2 != 0 {
        return (pass2, emitter.required as u64);
    }
    let rc = if emitter.overflow { 1 } else { 0 };
    (rc, emitter.required as u64)
}

fn scan_jar_impl(
    path_ptr: *const u8,
    path_len: usize,
    jdk_feature: c_int,
    out_ptr: *mut u8,
    out_cap: usize,
    required_ptr: *mut u64,
    counts_ptr: *mut u64,
) -> c_int {
    if path_ptr.is_null() || required_ptr.is_null() || counts_ptr.is_null() {
        return -1;
    }
    if out_ptr.is_null() && out_cap > 0 {
        return -1;
    }
    // required_ptr points at one u64 and counts_ptr at five; both are caller-owned writable scratch.
    unsafe {
        *required_ptr = 0;
        for index in 0..5 {
            *counts_ptr.add(index) = 0;
        }
    }

    // path_ptr is a caller-owned UTF-8 buffer of path_len bytes, readable for the duration of the call.
    let path_bytes = unsafe { slice::from_raw_parts(path_ptr, path_len) };
    let Ok(path) = std::str::from_utf8(path_bytes) else {
        return -3;
    };
    let file = match File::open(Path::new(path)) {
        Ok(file) => file,
        Err(_) => return -3,
    };
    let len = match file.metadata() {
        Ok(metadata) => metadata.len(),
        Err(_) => return -4,
    };
    if len == 0 {
        return 0;
    }
    if len > usize::MAX as u64 {
        return -4;
    }
    let mapping = match unsafe { Mmap::map(&file) } {
        Ok(mapping) => mapping,
        Err(_) => return -5,
    };
    // out_ptr/out_cap describe a caller-owned writable buffer of out_cap bytes (out_cap == 0 means none).
    let out: &mut [u8] = if out_cap == 0 {
        &mut []
    } else {
        unsafe { slice::from_raw_parts_mut(out_ptr, out_cap) }
    };
    let mut counts = [0u64; 5];
    let feature = if jdk_feature > 0 && jdk_feature <= u16::MAX as c_int {
        jdk_feature as u16
    } else {
        21
    };
    let (rc, required) = scan_mapped(&mapping, feature, out, &mut counts);
    unsafe {
        *required_ptr = required;
        for (index, value) in counts.iter().enumerate() {
            *counts_ptr.add(index) = *value;
        }
    }
    rc
}

/// Walk a jar and write candidate records into the caller's buffer.
///
/// Returns 0 on success, 1 when the buffer was too small (`required` is the exact size), and a
/// negative value on an unsupported/unreadable jar; the Java side then falls back to its walk.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn oml_scan_jar_v1(
    path_ptr: *const u8,
    path_len: usize,
    jdk_feature: c_int,
    out_ptr: *mut u8,
    out_cap: usize,
    required_ptr: *mut u64,
    counts_ptr: *mut u64,
) -> c_int {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        scan_jar_impl(
            path_ptr,
            path_len,
            jdk_feature,
            out_ptr,
            out_cap,
            required_ptr,
            counts_ptr,
        )
    }))
    .unwrap_or(-100)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use zip::CompressionMethod;
    use zip::write::SimpleFileOptions;

    fn manifest_entry(text: &str) -> (&'static str, Vec<u8>) {
        ("META-INF/MANIFEST.MF", text.as_bytes().to_vec())
    }

    fn make_zip(entries: &[(&str, Vec<u8>, CompressionMethod)], manifest: Option<&str>) -> Vec<u8> {
        let mut cursor = std::io::Cursor::new(Vec::new());
        {
            let mut writer = zip::ZipWriter::new(&mut cursor);
            if let Some(text) = manifest {
                let (name, bytes) = manifest_entry(text);
                writer
                    .start_file(
                        name,
                        SimpleFileOptions::default()
                            .compression_method(CompressionMethod::Deflated),
                    )
                    .unwrap();
                writer.write_all(&bytes).unwrap();
            }
            for (name, bytes, method) in entries {
                writer
                    .start_file(
                        *name,
                        SimpleFileOptions::default().compression_method(*method),
                    )
                    .unwrap();
                writer.write_all(bytes).unwrap();
            }
            writer.finish().unwrap();
        }
        cursor.into_inner()
    }

    fn marker(annotation: &str) -> Vec<u8> {
        format!("padding {} padding", annotation).into_bytes()
    }

    type Record = (u8, u16, String, Vec<u8>);

    fn parse_records(out: &[u8], used: usize) -> Vec<Record> {
        let mut records = Vec::new();
        let mut offset = 0usize;
        while offset < used {
            let mask = out[offset];
            let version = u16::from_le_bytes([out[offset + 1], out[offset + 2]]);
            let name_len = u16::from_le_bytes([out[offset + 3], out[offset + 4]]) as usize;
            let name_start = offset + RECORD_HEADER;
            let name = String::from_utf8(out[name_start..name_start + name_len].to_vec()).unwrap();
            let mut next = name_start + name_len;
            let mut bytes = Vec::new();
            if mask & SCAN_MOD != 0 {
                let len =
                    u32::from_le_bytes([out[next], out[next + 1], out[next + 2], out[next + 3]])
                        as usize;
                next += 4;
                bytes = out[next..next + len].to_vec();
                next += len;
            }
            records.push((mask, version, name, bytes));
            offset = next;
        }
        records
    }

    fn scan(zip: &[u8]) -> (c_int, Vec<Record>, [u64; 5]) {
        let mut out = vec![0u8; 1 << 20];
        let mut counts = [0u64; 5];
        let (rc, required) = scan_mapped(zip, 27, &mut out, &mut counts);
        if rc != 0 {
            return (rc, Vec::new(), counts);
        }
        (rc, parse_records(&out, required as usize), counts)
    }

    #[test]
    fn constant_pool_stops_at_the_pool_end() {
        let mut class = vec![0xCA, 0xFE, 0xBA, 0xBE, 0, 0, 0, 61, 0, 3];
        class.extend_from_slice(&[1, 0, 2, b'H', b'i']);
        class.extend_from_slice(&[7, 0, 1]);
        assert_eq!(constant_pool_end(&class), Some(18));
    }

    #[test]
    fn marker_match_is_constant_pool_bounded() {
        let bytes = b"....Lorg/ohmyloader/api/core/Mod;....Lorg/ohmyloader/api/core/hook/Hooked;";
        assert_eq!(markers_in(bytes), SCAN_MOD | SCAN_HOOK);
    }

    #[test]
    fn manifest_value_is_case_insensitive() {
        assert!(manifest_is_multi_release(
            b"Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"
        ));
        assert!(!manifest_is_multi_release(
            b"Manifest-Version: 1.0\r\nMulti-Release: false\r\n\r\n"
        ));
    }

    #[test]
    fn deflated_annotations_are_found() {
        let zip = make_zip(
            &[
                (
                    "org/example/Mod.class",
                    marker("Lorg/ohmyloader/api/core/Mod;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "org/example/Rules.class",
                    marker("Lorg/ohmyloader/api/core/inject/RuleSource;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "org/example/Plain.class",
                    b"nothing here".to_vec(),
                    CompressionMethod::Deflated,
                ),
            ],
            None,
        );
        let (rc, records, counts) = scan(&zip);
        assert_eq!(rc, 0);
        assert_eq!(records.len(), 2);
        assert_eq!(counts[3], 3);
        assert_eq!(counts[0], 1);
        assert_eq!(counts[1], 1);
        assert!(
            records
                .iter()
                .all(|(mask, version, _, _)| *version == 0 && mask & SCAN_RESERVED == 0)
        );
    }

    #[test]
    fn stored_entries_are_scanned_without_inflating() {
        let zip = make_zip(
            &[(
                "org/example/Mod.class",
                marker("Lorg/ohmyloader/api/core/Mod;"),
                CompressionMethod::Stored,
            )],
            None,
        );
        let (rc, records, _) = scan(&zip);
        assert_eq!(rc, 0);
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].0 & SCAN_MOD, SCAN_MOD);
    }

    #[test]
    fn mr_jar_winner_shadows_the_base_copy() {
        let zip = make_zip(
            &[
                (
                    "org/example/Mod.class",
                    marker("Lorg/ohmyloader/api/core/Mod;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "META-INF/versions/17/org/example/Mod.class",
                    marker("Lorg/ohmyloader/api/core/inject/RuleSource;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "META-INF/versions/21/org/example/Mod.class",
                    marker("Lorg/ohmyloader/api/core/hook/Hooked;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "META-INF/versions/21/org/example/Only21.class",
                    marker("Lorg/ohmyloader/api/core/Mod;"),
                    CompressionMethod::Deflated,
                ),
            ],
            Some("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"),
        );
        let (rc, records, _) = scan(&zip);
        assert_eq!(rc, 0);
        let hook = records
            .iter()
            .find(|(_, _, name, _)| name == "org/example/Mod")
            .expect("the versioned copy wins");
        assert_eq!(hook.0 & SCAN_HOOK, SCAN_HOOK);
        assert_eq!(hook.1, 21);
        assert!(
            records
                .iter()
                .any(|(_, version, name, _)| name == "org/example/Only21" && *version == 21)
        );
        assert_eq!(
            records
                .iter()
                .filter(|(_, _, name, _)| name == "org/example/Mod")
                .count(),
            1
        );
    }

    #[test]
    fn a_jar_without_multi_release_keeps_the_base_copy() {
        let zip = make_zip(
            &[
                (
                    "org/example/Mod.class",
                    marker("Lorg/ohmyloader/api/core/Mod;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "META-INF/versions/21/org/example/Mod.class",
                    marker("Lorg/ohmyloader/api/core/hook/Hooked;"),
                    CompressionMethod::Deflated,
                ),
            ],
            None,
        );
        let (rc, records, _) = scan(&zip);
        assert_eq!(rc, 0);
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].0 & SCAN_MOD, SCAN_MOD);
        assert_eq!(records[0].1, 0);
    }

    #[test]
    fn reserved_namespace_is_reported() {
        let zip = make_zip(
            &[
                (
                    "org/ohmyloader/api/core/Mod.class",
                    marker("Lorg/ohmyloader/api/core/Mod;"),
                    CompressionMethod::Deflated,
                ),
                (
                    "META-INF/versions/21/org/ohmyloader/api/core/Hooked.class",
                    marker("Lorg/ohmyloader/api/core/hook/Hooked;"),
                    CompressionMethod::Deflated,
                ),
            ],
            Some("Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n"),
        );
        let (rc, records, counts) = scan(&zip);
        assert_eq!(rc, 0);
        assert_eq!(counts[4], 2);
        assert!(
            records
                .iter()
                .all(|(mask, _, _, _)| mask & SCAN_RESERVED != 0)
        );
    }

    #[test]
    fn a_small_output_buffer_reports_the_exact_required_size() {
        let zip = make_zip(
            &[(
                "org/example/Mod.class",
                marker("Lorg/ohmyloader/api/core/Mod;"),
                CompressionMethod::Deflated,
            )],
            None,
        );
        let mut small = [0u8; 8];
        let mut counts = [0u64; 5];
        let (rc, required) = scan_mapped(&zip, 27, &mut small, &mut counts);
        assert_eq!(rc, 1);
        assert!(required > small.len() as u64);
    }
}
