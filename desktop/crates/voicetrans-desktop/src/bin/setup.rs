#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(not(target_os = "windows"))]
fn main() {
    eprintln!("VoiceTrans-Setup is only supported on Windows.");
}

#[cfg(target_os = "windows")]
mod win_setup {
    use egui::{Color32, RichText};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

static APP_EXE: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/payload_app.bin"));
static APP_ICO: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/app.ico"));
static MODELS_JSON: &[u8] = include_bytes!("../../../../../app/src/main/assets/models/models.json");
// Only the catalog is embedded: the app downloads the model files it lists on first run (model_manager).

fn default_install_dir() -> PathBuf {
    let local_app_data = env::var("LOCALAPPDATA").unwrap_or_else(|_| {
        let user = env::var("USERPROFILE").unwrap_or_else(|_| "C:\\Users\\Public".into());
        format!("{user}\\AppData\\Local")
    });
    PathBuf::from(local_app_data)
        .join("Programs")
        .join("VoiceTrans")
}

fn perform_install(
    install_dir: &Path,
    create_desktop_shortcut: bool,
    create_start_menu_shortcut: bool,
    launch_after: bool,
) -> Result<String, String> {
    // Stop any running instance before overwriting VoiceTrans.exe
    let _ = Command::new("taskkill")
        .args(["/F", "/IM", "VoiceTrans.exe"])
        .output();

    fs::create_dir_all(install_dir.join("models/vad")).map_err(|e| e.to_string())?;
    fs::create_dir_all(install_dir.join("models/tagging")).map_err(|e| e.to_string())?;
    fs::create_dir_all(install_dir.join("models/asr")).map_err(|e| e.to_string())?;

    let exe_path = install_dir.join("VoiceTrans.exe");
    let ico_path = install_dir.join("app.ico");

    if !APP_EXE.is_empty() {
        fs::write(&exe_path, APP_EXE).map_err(|e| format!("Không thể ghi VoiceTrans.exe: {e}"))?;
    } else {
        // Fallback if run from build dir without embedded payload
        let sibling = env::current_exe()
            .ok()
            .and_then(|p| p.parent().map(|d| d.join("VoiceTrans.exe")));
        if let Some(src) = sibling.filter(|p| p.exists()) {
            fs::copy(&src, &exe_path).map_err(|e| e.to_string())?;
        } else {
            return Err("Không tìm thấy payload VoiceTrans.exe".into());
        }
    }

    fs::write(&ico_path, APP_ICO).map_err(|e| e.to_string())?;
    fs::write(install_dir.join("models/models.json"), MODELS_JSON).map_err(|e| e.to_string())?;

    // Write uninstall helper script
    let uninstall_cmd = install_dir.join("uninstall.cmd");
    let uninstall_script = format!(
        "@echo off\r\ntaskkill /F /IM VoiceTrans.exe >nul 2>&1\r\npowershell -NoProfile -ExecutionPolicy Bypass -Command \"\
        $d = [Environment]::GetFolderPath('Desktop'); Remove-Item -Force (Join-Path $d 'VoiceTrans VI.lnk') -ErrorAction SilentlyContinue; \
        $s = [Environment]::GetFolderPath('Programs'); Remove-Item -Force (Join-Path $s 'VoiceTrans VI.lnk') -ErrorAction SilentlyContinue; \
        Remove-Item -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\VoiceTransVI' -Force -ErrorAction SilentlyContinue\"\r\n\
        cd /d \"%TEMP%\"\r\nrmdir /s /q \"{}\"\r\n",
        install_dir.display()
    );
    let _ = fs::write(&uninstall_cmd, uninstall_script);

    // Create shortcuts & registry entry via PowerShell COM WScript.Shell
    let exe_str = exe_path.display().to_string().replace('\'', "''");
    let ico_str = ico_path.display().to_string().replace('\'', "''");
    let dir_str = install_dir.display().to_string().replace('\'', "''");
    let uninst_str = uninstall_cmd.display().to_string().replace('\'', "''");

    let ps_script = format!(
        "$ws = New-Object -ComObject WScript.Shell; \
         if (${create_desktop}) {{ \
             $desk = [Environment]::GetFolderPath('Desktop'); \
             $lnk = $ws.CreateShortcut((Join-Path $desk 'VoiceTrans VI.lnk')); \
             $lnk.TargetPath = '{exe_str}'; \
             $lnk.WorkingDirectory = '{dir_str}'; \
             $lnk.IconLocation = '{ico_str}'; \
             $lnk.Description = 'VoiceTrans VI — Dịch phụ đề trực tiếp'; \
             $lnk.Save(); \
         }} \
         if (${create_start}) {{ \
             $prog = [Environment]::GetFolderPath('Programs'); \
             $lnk2 = $ws.CreateShortcut((Join-Path $prog 'VoiceTrans VI.lnk')); \
             $lnk2.TargetPath = '{exe_str}'; \
             $lnk2.WorkingDirectory = '{dir_str}'; \
             $lnk2.IconLocation = '{ico_str}'; \
             $lnk2.Description = 'VoiceTrans VI — Dịch phụ đề trực tiếp'; \
             $lnk2.Save(); \
         }} \
         $regPath = 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\VoiceTransVI'; \
         New-Item -Path $regPath -Force | Out-Null; \
         Set-ItemProperty -Path $regPath -Name 'DisplayName' -Value 'VoiceTrans VI'; \
         Set-ItemProperty -Path $regPath -Name 'DisplayVersion' -Value '0.2.0'; \
         Set-ItemProperty -Path $regPath -Name 'Publisher' -Value 'VoiceTrans Contributors'; \
         Set-ItemProperty -Path $regPath -Name 'DisplayIcon' -Value '{ico_str}'; \
         Set-ItemProperty -Path $regPath -Name 'InstallLocation' -Value '{dir_str}'; \
         Set-ItemProperty -Path $regPath -Name 'UninstallString' -Value '\"{uninst_str}\"'; \
         Set-ItemProperty -Path $regPath -Name 'NoModify' -Value 1 -Type DWord; \
         Set-ItemProperty -Path $regPath -Name 'NoRepair' -Value 1 -Type DWord;",
        create_desktop = if create_desktop_shortcut { "true" } else { "false" },
        create_start = if create_start_menu_shortcut { "true" } else { "false" },
    );

    let output = Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            &ps_script,
        ])
        .output()
        .map_err(|e| format!("Lỗi tạo shortcut: {e}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("Không thể tạo shortcut: {stderr}"));
    }

    if launch_after {
        let _ = Command::new(&exe_path).current_dir(install_dir).spawn();
    }

    Ok(format!(
        "Đã cài đặt thành công vào:\n{}\n(Đã tạo biểu tượng 'VoiceTrans VI' ngoài màn hình Desktop)",
        install_dir.display()
    ))
}

fn perform_uninstall(install_dir: &Path) -> Result<String, String> {
    let _ = Command::new("taskkill")
        .args(["/F", "/IM", "VoiceTrans.exe"])
        .output();

    let ps_script = "$desk = [Environment]::GetFolderPath('Desktop'); \
         Remove-Item -Force (Join-Path $desk 'VoiceTrans VI.lnk') -ErrorAction SilentlyContinue; \
         $prog = [Environment]::GetFolderPath('Programs'); \
         Remove-Item -Force (Join-Path $prog 'VoiceTrans VI.lnk') -ErrorAction SilentlyContinue; \
         Remove-Item -Path 'HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\VoiceTransVI' -Force -ErrorAction SilentlyContinue;";

    let _ = Command::new("powershell")
        .args([
            "-NoProfile",
            "-NonInteractive",
            "-WindowStyle",
            "Hidden",
            "-ExecutionPolicy",
            "Bypass",
            "-Command",
            ps_script,
        ])
        .output();

    if install_dir.exists() {
        let _ = fs::remove_dir_all(install_dir);
    }

    Ok("Đã gỡ cài đặt VoiceTrans VI và xóa biểu tượng ngoài màn hình Desktop.".into())
}

struct SetupWizardApp {
    install_dir: String,
    desktop_shortcut: bool,
    start_menu_shortcut: bool,
    launch_after: bool,
    status_message: Option<(bool, String)>,
}

impl Default for SetupWizardApp {
    fn default() -> Self {
        Self {
            install_dir: default_install_dir().display().to_string(),
            desktop_shortcut: true,
            start_menu_shortcut: true,
            launch_after: true,
            status_message: None,
        }
    }
}

impl eframe::App for SetupWizardApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        let mut visuals = egui::Visuals::dark();
        visuals.panel_fill = Color32::from_rgb(18, 20, 26);
        ctx.set_visuals(visuals);

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.heading(
                RichText::new("Trình cài đặt VoiceTrans VI (Windows)")
                    .size(20.0)
                    .color(Color32::from_rgb(124, 176, 255)),
            );
            ui.label(
                RichText::new(
                    "Cài đặt ứng dụng dịch phụ đề trực tiếp (hỗ trợ đa màn hình, nhấn giữ di chuyển Float Sub ở mọi chế độ).",
                )
                .size(13.0)
                .color(Color32::from_rgb(175, 185, 205)),
            );
            ui.add_space(12.0);
            ui.separator();
            ui.add_space(10.0);

            ui.label(RichText::new("Thư mục cài đặt:").strong());
            ui.add(egui::TextEdit::singleline(&mut self.install_dir).desired_width(f32::INFINITY));

            ui.add_space(10.0);
            ui.checkbox(
                &mut self.desktop_shortcut,
                "🖥 Tạo biểu tượng ngoài màn hình nền (Desktop Shortcut)",
            );
            ui.checkbox(
                &mut self.start_menu_shortcut,
                "📌 Tạo biểu tượng trong Start Menu",
            );
            ui.checkbox(
                &mut self.launch_after,
                "🚀 Mở VoiceTrans VI ngay sau khi cài đặt xong",
            );

            ui.add_space(16.0);
            ui.horizontal(|ui| {
                if ui
                    .button(
                        RichText::new("🚀 Cài đặt ngay")
                            .size(15.0)
                            .strong()
                            .color(Color32::from_rgb(124, 176, 255)),
                    )
                    .clicked()
                {
                    let dir = PathBuf::from(&self.install_dir);
                    match perform_install(
                        &dir,
                        self.desktop_shortcut,
                        self.start_menu_shortcut,
                        self.launch_after,
                    ) {
                        Ok(msg) => self.status_message = Some((true, msg)),
                        Err(err) => self.status_message = Some((false, err)),
                    }
                }

                ui.add_space(8.0);
                if ui
                    .button(
                        RichText::new("🗑 Gỡ cài đặt")
                            .size(14.0)
                            .color(Color32::from_rgb(255, 130, 130)),
                    )
                    .clicked()
                {
                    let dir = PathBuf::from(&self.install_dir);
                    match perform_uninstall(&dir) {
                        Ok(msg) => self.status_message = Some((true, msg)),
                        Err(err) => self.status_message = Some((false, err)),
                    }
                }
            });

            if let Some((ok, msg)) = &self.status_message {
                ui.add_space(12.0);
                ui.separator();
                ui.add_space(6.0);
                let color = if *ok {
                    Color32::from_rgb(120, 230, 150)
                } else {
                    Color32::from_rgb(255, 120, 120)
                };
                ui.label(RichText::new(msg).size(13.5).color(color));
            }
        });
    }
}

fn configure_windows_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let windir = env::var("WINDIR").unwrap_or_else(|_| "C:\\Windows".to_string());
    let fonts_dir = Path::new(&windir).join("Fonts");

    for &(name, filename, primary) in &[
        ("segoeui", "segoeui.ttf", true),
        ("seguisym", "seguisym.ttf", false),
    ] {
        let path = fonts_dir.join(filename);
        if let Ok(bytes) = fs::read(&path) {
            fonts
                .font_data
                .insert(name.to_owned(), egui::FontData::from_owned(bytes).into());
            if let Some(prop) = fonts.families.get_mut(&egui::FontFamily::Proportional) {
                if primary {
                    prop.insert(0, name.to_owned());
                } else {
                    prop.push(name.to_owned());
                }
            }
        }
    }
    ctx.set_fonts(fonts);
}

pub fn main() -> eframe::Result<()> {
    let args: Vec<String> = env::args().collect();
    let dir = default_install_dir();

    if args.iter().any(|a| a == "--silent" || a == "--install") {
        let launch = args.iter().any(|a| a == "--launch");
        let _ = perform_install(&dir, true, true, launch);
        return Ok(());
    }
    if args.iter().any(|a| a == "--uninstall") {
        let _ = perform_uninstall(&dir);
        return Ok(());
    }

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("VoiceTrans VI — Trình cài đặt (Setup)")
            .with_inner_size([520.0, 340.0])
            .with_resizable(false),
        ..Default::default()
    };

    eframe::run_native(
        "VoiceTrans VI Setup",
        options,
        Box::new(|cc| {
            configure_windows_fonts(&cc.egui_ctx);
            Ok(Box::new(SetupWizardApp::default()))
        }),
    )
}
} // mod win_setup

#[cfg(target_os = "windows")]
pub fn main() -> eframe::Result<()> {
    win_setup::main()
}
