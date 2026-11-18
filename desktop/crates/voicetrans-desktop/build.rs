use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os != "windows" {
        return;
    }

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
    let repo_root = manifest_dir.join("../../..");

    // 1. Generate multi-resolution app.ico from Android mipmap PNGs
    let mipmaps: &[(&str, u8)] = &[
        ("app/src/main/res/mipmap-mdpi/ic_launcher.png", 48),
        ("app/src/main/res/mipmap-xhdpi/ic_launcher.png", 96),
        ("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", 192),
    ];

    let mut images: Vec<(u8, Vec<u8>)> = Vec::new();
    for &(rel_path, size) in mipmaps {
        let p = repo_root.join(rel_path);
        println!("cargo:rerun-if-changed={}", p.display());
        if let Ok(bytes) = fs::read(&p) {
            images.push((size, bytes));
        }
    }

    let ico_bytes = build_ico(&images);
    let ico_path = out_dir.join("app.ico");
    let _ = fs::write(&ico_path, ico_bytes);

    // 1b. Compile Windows resource (.rc) with embedded icon & VersionInfo so Task Manager & Explorer show it
    let rc_path = out_dir.join("app.rc");
    let ico_escaped = ico_path.display().to_string().replace('\\', "/");
    let rc_content = format!(
        "1 ICON \"{ico_escaped}\"\n\
         1 VERSIONINFO\n\
         FILEVERSION 0,2,0,0\n\
         PRODUCTVERSION 0,2,0,0\n\
         FILEOS 0x40004\n\
         FILETYPE 0x1\n\
         BEGIN\n\
           BLOCK \"StringFileInfo\"\n\
           BEGIN\n\
             BLOCK \"040904B0\"\n\
             BEGIN\n\
               VALUE \"FileDescription\", \"VoiceTrans VI\"\n\
               VALUE \"ProductName\", \"VoiceTrans VI\"\n\
               VALUE \"FileVersion\", \"0.2.0\"\n\
               VALUE \"ProductVersion\", \"0.2.0\"\n\
             END\n\
           END\n\
           BLOCK \"VarFileInfo\"\n\
           BEGIN\n\
             VALUE \"Translation\", 0x0409, 1200\n\
           END\n\
         END\n"
    );
    let _ = fs::write(&rc_path, rc_content);

    let target = env::var("TARGET").unwrap_or_default();
    if target.contains("gnu") {
        let res_obj = out_dir.join("app_res.o");
        let windres_candidates = ["x86_64-w64-mingw32-windres", "llvm-windres", "windres"];
        for tool in windres_candidates {
            if let Ok(status) = std::process::Command::new(tool)
                .args([
                    rc_path.to_str().unwrap(),
                    "-O",
                    "coff",
                    "-o",
                    res_obj.to_str().unwrap(),
                ])
                .status()
            {
                if status.success() {
                    println!("cargo:rustc-link-arg={}", res_obj.display());
                    break;
                }
            }
        }
    } else if target.contains("msvc") {
        let res_file = out_dir.join("app.res");
        if let Ok(status) = std::process::Command::new("rc.exe")
            .args(["/fo", res_file.to_str().unwrap(), rc_path.to_str().unwrap()])
            .status()
        {
            if status.success() {
                println!("cargo:rustc-link-arg={}", res_file.display());
            }
        }
    }

    // 2. Embed compiled VoiceTrans.exe payload if available (for standalone VoiceTrans-Setup.exe)
    let dist_exe = manifest_dir.join("../../dist/VoiceTrans.exe");
    println!("cargo:rerun-if-changed={}", dist_exe.display());
    let payload_out = out_dir.join("payload_app.bin");
    if dist_exe.exists() {
        let _ = fs::copy(&dist_exe, &payload_out);
    } else {
        let _ = fs::write(&payload_out, &[]);
    }
}

fn build_ico(images: &[(u8, Vec<u8>)]) -> Vec<u8> {
    let count = images.len() as u16;
    let mut ico = Vec::new();
    ico.extend_from_slice(&0u16.to_le_bytes());
    ico.extend_from_slice(&1u16.to_le_bytes());
    ico.extend_from_slice(&count.to_le_bytes());

    let mut offset = 6u32 + (images.len() as u32) * 16;
    for (size, data) in images {
        let len = data.len() as u32;
        ico.push(*size);
        ico.push(*size);
        ico.push(0);
        ico.push(0);
        ico.extend_from_slice(&1u16.to_le_bytes());
        ico.extend_from_slice(&32u16.to_le_bytes());
        ico.extend_from_slice(&len.to_le_bytes());
        ico.extend_from_slice(&offset.to_le_bytes());
        offset += len;
    }

    for (_, data) in images {
        ico.extend_from_slice(data);
    }
    ico
}
