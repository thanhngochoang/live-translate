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

pub fn check_missing_components(models_dir: &Path) -> Vec<String> {
    let mut missing = Vec::new();
    let ort_path = resolve_ort_lib_path(models_dir);
    if !ort_path.exists() {
        missing.push(ort_lib_filename().to_string());
    }
    let required = [
        "vad/silero_vad.onnx",
        "asr-sv/tokens.txt",
        "asr-sv/model.int8.onnx",
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

    // 1. Download ONNX Runtime shared library if missing
    let ort_path = resolve_ort_lib_path(models_dir);
    if !ort_path.exists() {
        download_onnxruntime(&ort_path, on_progress)?;
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

fn download_onnxruntime(dest: &Path, on_progress: &dyn Fn(f32, String)) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    let (url, inner_lib) = (
        "https://files.pythonhosted.org/packages/dd/80/76979e0b744307d488c79e41051117634b956612cc731f1028eb17ee7294/onnxruntime-1.20.1-cp312-cp312-win_amd64.whl",
        "onnxruntime/capi/onnxruntime.dll",
    );
    #[cfg(target_os = "linux")]
    let (url, inner_lib) = (
        "https://files.pythonhosted.org/packages/47/42/2f71f5680834688a9c81becbe5c5bb996fd33eaed5c66ae0606c3b1d6a02/onnxruntime-1.20.1-cp312-cp312-manylinux_2_27_x86_64.manylinux_2_28_x86_64.whl",
        "onnxruntime/capi/libonnxruntime.so",
    );
    #[cfg(target_os = "macos")]
    let (url, inner_lib) = (
        "https://files.pythonhosted.org/packages/e5/39/9335e0874f68f7d27103cbffc0e235e32e26759202df6085716375c078bb/onnxruntime-1.20.1-cp312-cp312-macosx_13_0_universal2.whl",
        "onnxruntime/capi/libonnxruntime",
    );

    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let tmp = dest.with_extension("pkg.tmp");
    on_progress(0.02, "Đang tải ONNX Runtime Engine...".into());
    download_to_file(url, &tmp, &|frac| {
        on_progress(
            0.02 + frac * 0.13,
            format!("Đang tải ONNX Runtime ({:.0}%)...", frac * 100.0),
        );
    })?;

    on_progress(0.15, "Đang giải nén ONNX Runtime...".into());
    let file = File::open(&tmp).map_err(|e| e.to_string())?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("Lỗi giải nén zip: {e}"))?;
    let mut found = false;
    for i in 0..archive.len() {
        let mut entry = archive.by_index(i).map_err(|e| e.to_string())?;
        let name = entry.name().to_string();
        if name == inner_lib || name.starts_with(&format!("{inner_lib}.")) {
            let mut out = File::create(dest).map_err(|e| e.to_string())?;
            io::copy(&mut entry, &mut out).map_err(|e| e.to_string())?;
            found = true;
            break;
        }
    }
    let _ = fs::remove_file(&tmp);
    if !found {
        return Err(format!("Không tìm thấy {inner_lib} trong gói tải về."));
    }

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
