use crc32fast::Hasher;
use jni::objects::JClass;
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;
use std::fs::File;
use std::io::Read;
use std::os::fd::FromRawFd;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

const HEADER_LEN: usize = 64;
const AML_MAGIC: u32 = 0x27B5_1956;
const CHUNK_LEN: usize = 1024 * 1024;
const MAX_DESCRIPTOR_COUNT: u32 = 4096;
const V1_DESCRIPTOR_LEN: usize = 128;
const V2_DESCRIPTOR_LEN: usize = 576;

#[derive(Debug)]
struct Descriptor {
    file_type: u32,
    data_offset: u64,
    data_size: u64,
    main_type: String,
    sub_type: String,
    verify: u32,
}

fn read_u32(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn read_u64(bytes: &[u8], offset: usize) -> u64 {
    u64::from_le_bytes(bytes[offset..offset + 8].try_into().unwrap())
}

fn read_fixed_text(bytes: &[u8]) -> String {
    let end = bytes
        .iter()
        .position(|byte| *byte == 0)
        .unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).trim().to_string()
}

fn parse_descriptors(
    bytes: &[u8],
    version: u32,
    item_count: u32,
    declared_size: u64,
) -> Result<Vec<Descriptor>, String> {
    let (descriptor_len, text_len) = match version {
        0 | 1 => (V1_DESCRIPTOR_LEN, 32),
        2 => (V2_DESCRIPTOR_LEN, 256),
        _ => return Err(format!("unsupported descriptor version {version}")),
    };
    if item_count == 0 || item_count > MAX_DESCRIPTOR_COUNT {
        return Err(format!("implausible descriptor count {item_count}"));
    }
    let table_len = (item_count as usize)
        .checked_mul(descriptor_len)
        .ok_or_else(|| "descriptor table size overflow".to_string())?;
    let table_end = (HEADER_LEN as u64)
        .checked_add(table_len as u64)
        .ok_or_else(|| "descriptor table offset overflow".to_string())?;
    if table_end > declared_size {
        return Err(format!(
            "descriptor table ends at {table_end}, beyond image size {declared_size}"
        ));
    }
    if bytes.len() != table_len {
        return Err("descriptor table could not be read completely".to_string());
    }

    let mut descriptors = Vec::with_capacity(item_count as usize);
    for index in 0..item_count as usize {
        let start = index * descriptor_len;
        let descriptor = &bytes[start..start + descriptor_len];
        let file_type = read_u32(descriptor, 4);
        let data_offset = read_u64(descriptor, 16);
        let data_size = read_u64(descriptor, 24);
        let main_start = 32;
        let sub_start = main_start + text_len;
        let verify_offset = sub_start + text_len;
        let payload_end = data_offset
            .checked_add(data_size)
            .ok_or_else(|| format!("descriptor[{index}] payload range overflow"))?;
        if data_size > 0 && data_offset < table_end {
            return Err(format!(
                "descriptor[{index}] payload begins before the descriptor table ends"
            ));
        }
        if payload_end > declared_size {
            return Err(format!(
                "descriptor[{index}] payload range ends at {payload_end}, beyond image size {declared_size}"
            ));
        }

        descriptors.push(Descriptor {
            file_type,
            data_offset,
            data_size,
            main_type: read_fixed_text(&descriptor[main_start..sub_start]),
            sub_type: read_fixed_text(&descriptor[sub_start..verify_offset]),
            verify: read_u32(descriptor, verify_offset),
        });
    }
    Ok(descriptors)
}

fn file_type_label(file_type: u32) -> String {
    match file_type {
        0x000 => "normal".to_string(),
        0x0fe => "sparse".to_string(),
        0x1fe => "ubi".to_string(),
        0x2fe => "ubifs".to_string(),
        other => format!("unknown 0x{other:x}"),
    }
}

fn validate_fd(fd: jint, reported_size: jlong) -> String {
    if fd < 0 {
        return "INVALID: no readable file descriptor".to_string();
    }

    // Ownership is transferred from ParcelFileDescriptor.detachFd(); File closes it on return.
    let mut file = unsafe { File::from_raw_fd(fd) };
    let mut header = [0u8; HEADER_LEN];
    if let Err(err) = file.read_exact(&mut header) {
        return format!("INVALID: could not read Amlogic header ({err})");
    }

    let expected_crc = read_u32(&header, 0);
    let version = read_u32(&header, 4);
    let magic = read_u32(&header, 8);
    let declared_size = read_u64(&header, 12);
    let item_count = read_u32(&header, 24);

    if magic != AML_MAGIC {
        return format!("INVALID: unrecognized image magic 0x{magic:08x}");
    }
    if version > 2 {
        return format!(
            "INVALID: unsupported Amlogic image version {version}; supported versions are 0, 1, and 2"
        );
    }
    if declared_size < HEADER_LEN as u64 {
        return "INVALID: declared image size is smaller than its header".to_string();
    }
    if item_count == 0 || item_count > MAX_DESCRIPTOR_COUNT {
        return format!("INVALID: implausible descriptor count {item_count}");
    }
    if reported_size > 0 && declared_size != reported_size as u64 {
        return format!(
            "INVALID: header says {declared_size} bytes but provider reports {reported_size}"
        );
    }

    let descriptor_len = if version == 2 {
        V2_DESCRIPTOR_LEN
    } else {
        V1_DESCRIPTOR_LEN
    };
    let table_len = match (item_count as usize).checked_mul(descriptor_len) {
        Some(value) => value,
        None => return "INVALID: descriptor table size overflow".to_string(),
    };
    let table_end = match (HEADER_LEN as u64).checked_add(table_len as u64) {
        Some(value) => value,
        None => return "INVALID: descriptor table offset overflow".to_string(),
    };
    if table_end > declared_size {
        return format!(
            "INVALID: descriptor table ends at {table_end}, beyond image size {declared_size}"
        );
    }

    let mut descriptor_bytes = vec![0u8; table_len];
    if let Err(err) = file.read_exact(&mut descriptor_bytes) {
        return format!("INVALID: could not read Amlogic descriptors ({err})");
    }
    let descriptors = match parse_descriptors(&descriptor_bytes, version, item_count, declared_size)
    {
        Ok(value) => value,
        Err(err) => return format!("INVALID: {err}"),
    };

    // The public format note computes CRC32 over the full image excluding bytes 0..4,
    // then XORs the zlib-compatible result with 0xffffffff.
    let mut hasher = Hasher::new();
    hasher.update(&header[4..]);
    hasher.update(&descriptor_bytes);
    let mut total = table_end;
    let mut buffer = vec![0u8; CHUNK_LEN];
    loop {
        match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(count) => {
                hasher.update(&buffer[..count]);
                total = match total.checked_add(count as u64) {
                    Some(value) => value,
                    None => return "INVALID: image length overflow".to_string(),
                };
            }
            Err(err) => return format!("INVALID: image read failed ({err})"),
        }
    }

    if total != declared_size {
        return format!("INVALID: header says {declared_size} bytes but read {total}");
    }
    let actual_crc = hasher.finalize() ^ 0xffff_ffff;
    if actual_crc != expected_crc {
        return format!(
            "INVALID: CRC mismatch (header 0x{expected_crc:08x}, computed 0x{actual_crc:08x})"
        );
    }

    let partitions: Vec<String> = descriptors
        .iter()
        .filter(|item| item.main_type == "PARTITION")
        .map(|item| {
            format!(
                "{} ({}, {} bytes @0x{:x}{})",
                if item.sub_type.is_empty() {
                    "unnamed"
                } else {
                    item.sub_type.as_str()
                },
                file_type_label(item.file_type),
                item.data_size,
                item.data_offset,
                if item.verify != 0 {
                    ", verify flag set"
                } else {
                    ""
                }
            )
        })
        .collect();
    let partition_summary = if partitions.is_empty() {
        "none declared".to_string()
    } else {
        partitions.join("; ")
    };
    let boot_payloads: Vec<String> = descriptors
        .iter()
        .filter(|item| item.main_type == "USB" || item.main_type == "UBOOT")
        .filter_map(|item| {
            if item.sub_type.is_empty() {
                Some(item.main_type.clone())
            } else {
                Some(format!("{}:{}", item.main_type, item.sub_type))
            }
        })
        .collect();
    let boot_summary = if boot_payloads.is_empty() {
        "none declared".to_string()
    } else {
        boot_payloads.join(", ")
    };

    format!(
        "VALID_IMAGE: Amlogic image v{version}; {item_count} descriptors; {total} bytes; CRC OK. Partitions: {partition_summary}. Boot-related entries: {boot_summary}. This checks package structure only, not board compatibility, signature trust, or flashing safety."
    )
}

#[no_mangle]
pub extern "system" fn Java_com_cue_amlogicburn_NativeCore_validateImageFd(
    env: JNIEnv,
    _class: JClass,
    fd: jint,
    reported_size: jlong,
) -> jstring {
    let result = catch_unwind(AssertUnwindSafe(|| validate_fd(fd, reported_size)))
        .unwrap_or_else(|_| "INVALID: native validator encountered an internal error".to_string());
    match env.new_string(result) {
        Ok(value) => value.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

#[cfg(test)]
mod tests {
    use super::{
        parse_descriptors, validate_fd, AML_MAGIC, HEADER_LEN, V1_DESCRIPTOR_LEN, V2_DESCRIPTOR_LEN,
    };
    use crc32fast::Hasher;
    use std::fs::{remove_file, File};
    use std::io::Write;
    use std::os::fd::IntoRawFd;
    use std::path::PathBuf;

    fn synthetic_image(version: u32) -> Vec<u8> {
        let descriptor_len = if version == 2 {
            V2_DESCRIPTOR_LEN
        } else {
            V1_DESCRIPTOR_LEN
        };
        let text_len = if version == 2 { 256 } else { 32 };
        let payload = b"test-partition-payload";
        let data_offset = HEADER_LEN + descriptor_len;
        let total = data_offset + payload.len();
        let mut bytes = vec![0u8; total];
        bytes[4..8].copy_from_slice(&version.to_le_bytes());
        bytes[8..12].copy_from_slice(&AML_MAGIC.to_le_bytes());
        bytes[12..20].copy_from_slice(&(total as u64).to_le_bytes());
        bytes[20..24].copy_from_slice(&4u32.to_le_bytes());
        bytes[24..28].copy_from_slice(&1u32.to_le_bytes());

        let descriptor_start = HEADER_LEN;
        bytes[descriptor_start..descriptor_start + 4].copy_from_slice(&0u32.to_le_bytes());
        bytes[descriptor_start + 4..descriptor_start + 8].copy_from_slice(&0u32.to_le_bytes());
        bytes[descriptor_start + 16..descriptor_start + 24]
            .copy_from_slice(&(data_offset as u64).to_le_bytes());
        bytes[descriptor_start + 24..descriptor_start + 32]
            .copy_from_slice(&(payload.len() as u64).to_le_bytes());
        let main_start = descriptor_start + 32;
        bytes[main_start..main_start + "PARTITION".len()].copy_from_slice(b"PARTITION");
        let sub_start = main_start + text_len;
        bytes[sub_start..sub_start + "system".len()].copy_from_slice(b"system");
        bytes[data_offset..].copy_from_slice(payload);

        let mut hasher = Hasher::new();
        hasher.update(&bytes[4..]);
        let crc = hasher.finalize() ^ 0xffff_ffff;
        bytes[0..4].copy_from_slice(&crc.to_le_bytes());
        bytes
    }

    fn check_bytes(name: &str, bytes: &[u8]) -> String {
        let path: PathBuf = std::env::temp_dir().join(format!(
            "amlburn-{name}-{}-{}.img",
            std::process::id(),
            bytes.len()
        ));
        let mut file = File::create(&path).expect("create temp image");
        file.write_all(bytes).expect("write temp image");
        drop(file);
        let fd = File::open(&path)
            .expect("open temp image for reading")
            .into_raw_fd();
        let message = validate_fd(fd, bytes.len() as i64);
        remove_file(path).expect("remove temp image");
        message
    }

    #[test]
    fn accepts_and_parses_version_zero_image() {
        let image = synthetic_image(0);
        let message = check_bytes("valid-v0", &image);
        assert!(message.starts_with("VALID_IMAGE:"), "{message}");
        assert!(message.contains("system (normal"), "{message}");
    }

    #[test]
    fn accepts_and_parses_version_one_image() {
        let image = synthetic_image(1);
        let message = check_bytes("valid-v1", &image);
        assert!(message.starts_with("VALID_IMAGE:"), "{message}");
        assert!(message.contains("Partitions: system"), "{message}");
    }

    #[test]
    fn accepts_and_parses_version_two_image() {
        let image = synthetic_image(2);
        let message = check_bytes("valid-v2", &image);
        assert!(
            message.starts_with("VALID_IMAGE: Amlogic image v2;"),
            "{message}"
        );
        assert!(message.contains("system (normal"), "{message}");
    }

    #[test]
    fn rejects_unknown_version() {
        let image = synthetic_image(3);
        let message = check_bytes("version-three", &image);
        assert!(
            message.starts_with("INVALID: unsupported Amlogic image version 3"),
            "{message}"
        );
    }

    #[test]
    fn rejects_bad_crc() {
        let mut image = synthetic_image(2);
        image[0] ^= 0x01;
        let message = check_bytes("bad-crc", &image);
        assert!(message.starts_with("INVALID: CRC mismatch"), "{message}");
    }

    #[test]
    fn rejects_descriptor_payload_past_end_of_image() {
        let mut image = synthetic_image(2);
        let descriptor_start = HEADER_LEN;
        let invalid_offset = (image.len() as u64 + 10).to_le_bytes();
        image[descriptor_start + 16..descriptor_start + 24].copy_from_slice(&invalid_offset);
        let message = check_bytes("bad-range", &image);
        assert!(
            message.starts_with("INVALID: descriptor[0] payload range ends"),
            "{message}"
        );
    }

    #[test]
    fn rejects_descriptor_count_that_exceeds_table() {
        let mut image = synthetic_image(2);
        image[24..28].copy_from_slice(&2u32.to_le_bytes());
        let message = check_bytes("short-table", &image);
        assert!(
            message.starts_with("INVALID: descriptor table ends"),
            "{message}"
        );
    }

    #[test]
    fn descriptor_types_have_named_labels() {
        assert_eq!(super::file_type_label(0), "normal");
        assert_eq!(super::file_type_label(0xfe), "sparse");
        assert_eq!(super::file_type_label(0x1fe), "ubi");
        assert_eq!(super::file_type_label(0x2fe), "ubifs");
    }

    #[test]
    fn descriptor_parser_reads_v2_fields() {
        let image = synthetic_image(2);
        let descriptor_bytes = &image[HEADER_LEN..HEADER_LEN + V2_DESCRIPTOR_LEN];
        let parsed = parse_descriptors(descriptor_bytes, 2, 1, image.len() as u64)
            .expect("valid descriptor");
        assert_eq!(parsed[0].main_type, "PARTITION");
        assert_eq!(parsed[0].sub_type, "system");
        assert_eq!(
            parsed[0].data_offset,
            (HEADER_LEN + V2_DESCRIPTOR_LEN) as u64
        );
    }

    #[test]
    fn validates_external_image_if_path_is_set() {
        let Some(path) = std::env::var_os("AML_IMAGE_TEST_PATH") else {
            return;
        };
        let file = File::open(path).expect("open AML_IMAGE_TEST_PATH");
        let size = file.metadata().expect("read image size").len() as i64;
        let message = validate_fd(file.into_raw_fd(), size);
        println!("{message}");
        assert!(message.starts_with("VALID_IMAGE:"), "{message}");
    }
}
