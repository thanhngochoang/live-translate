use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, PartialEq)]
pub enum EngineState {
    Checking,
    Missing(Vec<String>),
    Downloading { progress: f32, detail: String },
    Ready,
    Error(String),
}

#[derive(Debug, Deserialize)]
struct ModelsConfig {
    archives: Vec<ArchiveEntry>,
    files: Vec<FileEntry>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ArchiveEntry {
    id: String,
    url: String,
    sha256: String,
    inner_dir: String,
    files: Vec<ArchiveMember>,
}

#[derive(Debug, Deserialize)]
struct ArchiveMember {
    inner: String,
    dest: String,
    sha256: String,
}

#[derive(Debug, Deserialize)]
struct FileEntry {
    id: String,
    url: String,
    sha256: String,
    dest: String,
}

static MODELS_JSON_DEFAULT: &str =
    include_str!("../../../../app/src/main/assets/models/models.json");

#[cfg(target_os = "windows")]
pub fn ort_lib_filename() -> &'static str {
    "onnxruntime.dll"
}
#[cfg(target_os = "linux")]
pub fn ort_lib_filename() -> &'static str {
    "libonnxruntime.so"
}
#[cfg(target_os = "macos")]
pub fn ort_lib_filename() -> &'static str {
    "libonnxruntime.dylib"
}

#[cfg(target_os = "windows")]
pub fn sherpa_c_api_filename() -> &'static str {
    "sherpa-onnx-c-api.dll"
}
#[cfg(target_os = "linux")]
pub fn sherpa_c_api_filename() -> &'static str {
    "libsherpa-onnx-c-api.so"
}
#[cfg(target_os = "macos")]
pub fn sherpa_c_api_filename() -> &'static str {
    "libsherpa-onnx-c-api.dylib"
}

pub fn resolve_models_dir() -> PathBuf {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let candidate = dir.join("models");
            if candidate.exists() {
                return candidate;
            }
        }
    }
    let repo_models = PathBuf::from("app/src/main/assets/models");
    if repo_models.exists() {
        return repo_models;
    }
    if let Some(proj) = directories::ProjectDirs::from("io.github", "thanhngochoang", "VoiceTrans")
    {
        return proj.data_local_dir().join("models");
    }
    PathBuf::from("models")
}

pub fn resolve_ort_lib_path(models_dir: &Path) -> PathBuf {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let beside_exe = dir.join(ort_lib_filename());
            if beside_exe.exists() {
                return beside_exe;
            }
        }
    }
    models_dir.join(ort_lib_filename())
}

pub fn resolve_sherpa_c_api_path(models_dir: &Path) -> PathBuf {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let beside_exe = dir.join(sherpa_c_api_filename());
            if beside_exe.exists() {
                return beside_exe;
            }
        }
    }
    models_dir.join(sherpa_c_api_filename())
}

pub fn check_missing_components(models_dir: &Path) -> Vec<String> {
    let mut missing = Vec::new();
    let ort_path = resolve_ort_lib_path(models_dir);
    if !ort_path.exists() {
        missing.push(ort_lib_filename().to_string());
    }
    let sherpa_path = resolve_sherpa_c_api_path(models_dir);
    if !sherpa_path.exists() {
        missing.push(sherpa_c_api_filename().to_string());
    }
    let required = [
        "vad/silero_vad.onnx",
        "asr/tokens.txt",
        "asr/encoder-epoch-99-avg-1.int8.onnx",
        "asr/decoder-epoch-99-avg-1.onnx",
        "asr/joiner-epoch-99-avg-1.int8.onnx",
    ];
    for rel in required {
        if !models_dir.join(rel).exists() {
            missing.push(rel.to_string());
        }
    }
    missing
}

pub fn spawn_download_missing(
    models_dir: PathBuf,
    state: Arc<Mutex<EngineState>>,
    on_update: impl Fn() + Send + 'static,
) {
    std::thread::spawn(move || {
        let set_state = |s: EngineState| {
            if let Ok(mut guard) = state.lock() {
                *guard = s;
            }
            on_update();
        };

        if let Err(err) = download_all_missing(&models_dir, &|progress, detail| {
            set_state(EngineState::Downloading { progress, detail });
        }) {
            set_state(EngineState::Error(err));
        } else {
            set_state(EngineState::Ready);
        }
    });
}

fn download_all_missing(
    models_dir: &Path,
    on_progress: &dyn Fn(f32, String),
) -> Result<(), String> {
    fs::create_dir_all(models_dir).map_err(|e| format!("Không tạo được thư mục models: {e}"))?;

    // 1. Download Sherpa ASR Engine & ONNX Runtime shared libraries if missing
    let ort_path = resolve_ort_lib_path(models_dir);
    let sherpa_path = resolve_sherpa_c_api_path(models_dir);
    if !ort_path.exists() || !sherpa_path.exists() {
        download_sherpa_engine(models_dir, on_progress)?;
    }

    // 2. Parse models.json
    let cfg_path = models_dir.join("models.json");
    let cfg_str = fs::read_to_string(&cfg_path).unwrap_or_else(|_| MODELS_JSON_DEFAULT.to_string());
    let cfg: ModelsConfig =
        serde_json::from_str(&cfg_str).map_err(|e| format!("Lỗi đọc models.json: {e}"))?;

    // 3. Direct files (e.g. silero_vad.onnx)
    for f in &cfg.files {
        let dest = models_dir.join(&f.dest);
        if verify_sha256(&dest, &f.sha256) {
            continue;
        }
        on_progress(0.15, format!("Đang tải {}...", f.id));
        if let Some(parent) = dest.parent() {
            fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        let tmp = dest.with_extension("tmp");
        download_to_file(&f.url, &tmp, &|frac| {
            on_progress(0.15 + frac * 0.15, format!("Đang tải {} ({:.0}%)...", f.id, frac * 100.0));
        })?;
        if !verify_sha256(&tmp, &f.sha256) {
            let _ = fs::remove_file(&tmp);
            return Err(format!("Sai mã kiểm tra SHA-256 cho {}", f.id));
        }
        fs::rename(&tmp, &dest).map_err(|e| e.to_string())?;
    }

    // 4. Archives (e.g. asr-sense-voice, audio-tagging)
    for arch in &cfg.archives {
        let all_ok = arch
            .files
            .iter()
            .all(|m| verify_sha256(&models_dir.join(&m.dest), &m.sha256));
        if all_ok {
            continue;
        }

        let tmp_arch = models_dir.join(format!("{}.tar.bz2.tmp", arch.id));
        on_progress(0.30, format!("Đang tải {}...", arch.id));
        download_to_file(&arch.url, &tmp_arch, &|frac| {
            on_progress(
                0.30 + frac * 0.55,
                format!("Đang tải {} ({:.0}%)...", arch.id, frac * 100.0),
            );
        })?;

        on_progress(0.86, format!("Đang kiểm tra SHA-256 {}...", arch.id));
        if !verify_sha256(&tmp_arch, &arch.sha256) {
            let _ = fs::remove_file(&tmp_arch);
            return Err(format!("Sai mã kiểm tra SHA-256 cho {}", arch.id));
        }

        on_progress(0.90, format!("Đang giải nén {}...", arch.id));
        extract_tar_bz2_members(&tmp_arch, &arch.inner_dir, &arch.files, models_dir)?;
        let _ = fs::remove_file(&tmp_arch);
    }

    let still_missing = check_missing_components(models_dir);
    if !still_missing.is_empty() {
        return Err(format!("Vẫn còn thiếu file: {}", still_missing.join(", ")));
    }
    on_progress(1.0, "Engine & Model đã sẵn sàng.".into());
    Ok(())
}

fn download_sherpa_engine(models_dir: &Path, on_progress: &dyn Fn(f32, String)) -> Result<(), String> {
    #[cfg(all(target_os = "windows", target_arch = "x86_64"))]
    let (url, sha256_expected, inner_prefix) = (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-win-x64-shared-MD-Release-lib.tar.bz2",
        "c3c78220db8bebf97d5f932496165bb3897b163410427d7c021013439c80f183",
        "sherpa-onnx-v2.0.1-win-x64-shared-MD-Release-lib/lib",
    );
    #[cfg(all(target_os = "linux", target_arch = "x86_64"))]
    let (url, sha256_expected, inner_prefix) = (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-linux-x64-shared-lib.tar.bz2",
        "532c45bbd41036d83cd70606797529aa8086c2053395a58967736c727625b120",
        "sherpa-onnx-v2.0.1-linux-x64-shared-lib/lib",
    );
    #[cfg(target_os = "macos")]
    let (url, sha256_expected, inner_prefix) = (
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-v2.0.1-osx-universal2-shared-lib.tar.bz2",
        "a3aa3e8a6c78bc06d98f8a5792a924e3d2a7c308c76db68aef358b9278366ac7",
        "sherpa-onnx-v2.0.1-osx-universal2-shared-lib/lib",
    );

    fs::create_dir_all(models_dir).map_err(|e| e.to_string())?;
    let tmp = models_dir.join("sherpa_engine.tar.bz2.tmp");
    on_progress(0.02, "Đang tải Sherpa Engine & ONNX Runtime (7 MB)...".into());
    download_to_file(url, &tmp, &|frac| {
        on_progress(
            0.02 + frac * 0.13,
            format!("Đang tải ASR Engine ({:.0}%)...", frac * 100.0),
        );
    })?;

    if !verify_sha256(&tmp, sha256_expected) {
        let _ = fs::remove_file(&tmp);
        return Err("Mã kiểm tra SHA-256 không khớp cho Sherpa ASR Engine.".into());
    }

    on_progress(0.15, "Đang giải nén Sherpa Engine & ONNX Runtime...".into());
    let file = File::open(&tmp).map_err(|e| e.to_string())?;
    let bz = bzip2_rs::DecoderReader::new(file);
    let mut tar = tar::Archive::new(bz);
    for entry in tar.entries().map_err(|e| format!("Lỗi đọc tar.bz2: {e}"))? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let path_str = entry
            .path()
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .replace('\\', "/");
        if path_str.starts_with(inner_prefix) {
            let filename = Path::new(&path_str).file_name().unwrap_or_default();
            let fname_str = filename.to_string_lossy();
            if fname_str.ends_with(".dll") || fname_str.ends_with(".so") || fname_str.ends_with(".dylib") {
                let dest = models_dir.join(filename);
                let mut out = File::create(&dest).map_err(|e| e.to_string())?;
                io::copy(&mut entry, &mut out).map_err(|e| e.to_string())?;
            }
        }
    }
    let _ = fs::remove_file(&tmp);
    Ok(())
}

fn https_agent() -> Result<ureq::Agent, String> {
    let connector = native_tls::TlsConnector::new()
        .map_err(|e| format!("Lỗi khởi tạo TLS: {e}"))?;
    Ok(ureq::AgentBuilder::new()
        .tls_connector(Arc::new(connector))
        .build())
}

fn download_to_file(
    url: &str,
    dest: &Path,
    on_progress: &dyn Fn(f32),
) -> Result<(), String> {
    let agent = https_agent()?;
    let resp = agent
        .get(url)
        .call()
        .map_err(|e| format!("Lỗi kết nối tải model: {e}"))?;
    let total: Option<u64> = resp
        .header("Content-Length")
        .and_then(|s| s.parse::<u64>().ok());

    let mut reader = resp.into_reader();
    let mut out = File::create(dest).map_err(|e| format!("Lỗi ghi file: {e}"))?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut downloaded = 0u64;

    loop {
        let n = reader.read(&mut buf).map_err(|e| format!("Lỗi đọc stream: {e}"))?;
        if n == 0 {
            break;
        }
        out.write_all(&buf[..n]).map_err(|e| format!("Lỗi ghi đĩa: {e}"))?;
        downloaded += n as u64;
        if let Some(tot) = total.filter(|&t| t > 0) {
            on_progress((downloaded as f32 / tot as f32).clamp(0.0, 1.0));
        }
    }
    out.flush().map_err(|e| e.to_string())?;
    Ok(())
}

fn extract_tar_bz2_members(
    archive_path: &Path,
    inner_dir: &str,
    members: &[ArchiveMember],
    models_dir: &Path,
) -> Result<(), String> {
    let file = File::open(archive_path).map_err(|e| e.to_string())?;
    let bz = bzip2_rs::DecoderReader::new(file);
    let mut tar = tar::Archive::new(bz);

    for entry in tar.entries().map_err(|e| format!("Lỗi đọc tar.bz2: {e}"))? {
        let mut entry = entry.map_err(|e| e.to_string())?;
        let path_str = entry
            .path()
            .map_err(|e| e.to_string())?
            .to_string_lossy()
            .replace('\\', "/");

        for m in members {
            let expected = format!("{inner_dir}/{}", m.inner);
            if path_str == expected {
                let dest = models_dir.join(&m.dest);
                if let Some(parent) = dest.parent() {
                    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
                }
                let mut out = File::create(&dest).map_err(|e| e.to_string())?;
                io::copy(&mut entry, &mut out).map_err(|e| e.to_string())?;
                if !verify_sha256(&dest, &m.sha256) {
                    let _ = fs::remove_file(&dest);
                    return Err(format!("SHA-256 không khớp sau giải nén: {}", m.dest));
                }
            }
        }
    }
    Ok(())
}

fn verify_sha256(path: &Path, expected_hex: &str) -> bool {
    let Ok(mut f) = File::open(path) else {
        return false;
    };
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        match f.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buf[..n]),
            Err(_) => return false,
        }
    }
    let digest = hasher.finalize();
    let hex: String = digest.iter().map(|b| format!("{b:02x}")).collect();
    hex.eq_ignore_ascii_case(expected_hex)
}
