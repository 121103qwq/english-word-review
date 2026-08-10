use base64::{engine::general_purpose::STANDARD as BASE64, Engine as _};
use serde::{Deserialize, Serialize};
use std::{collections::HashMap, path::Path, process::Command, time::Duration};

const KEYRING_SERVICE: &str = "com.englishrebuilt.wordreview";

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct HttpRequest {
    url: String,
    method: String,
    headers: Option<HashMap<String, String>>,
    body: Option<String>,
    body_base64: Option<String>,
    response_type: Option<String>,
    timeout_ms: Option<u64>,
}

#[derive(Serialize)]
struct HttpResponse {
    status: u16,
    headers: HashMap<String, String>,
    body: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SaveTextFileResponse {
    saved: bool,
    path: Option<String>,
}

#[tauri::command]
async fn native_http_request(request: HttpRequest) -> Result<HttpResponse, String> {
    let client = reqwest::Client::builder()
        .user_agent("EnglishWordReview/8.3.1")
        .timeout(Duration::from_millis(request.timeout_ms.unwrap_or(12_000)))
        .build()
        .map_err(|error| error.to_string())?;
    let method = reqwest::Method::from_bytes(request.method.as_bytes()).map_err(|error| error.to_string())?;
    let mut builder = client.request(method, &request.url);
    if let Some(headers) = request.headers {
        for (name, value) in headers {
            builder = builder.header(name, value);
        }
    }
    if let Some(body) = request.body_base64 {
        builder = builder.body(BASE64.decode(body).map_err(|error| error.to_string())?);
    } else if let Some(body) = request.body {
        builder = builder.body(body);
    }
    let response = builder.send().await.map_err(|error| error.to_string())?;
    let status = response.status().as_u16();
    let headers = response
        .headers()
        .iter()
        .map(|(name, value)| (name.as_str().to_ascii_lowercase(), value.to_str().unwrap_or_default().to_owned()))
        .collect();
    let bytes = response.bytes().await.map_err(|error| error.to_string())?;
    let body = if request.response_type.as_deref() == Some("base64") {
        BASE64.encode(bytes)
    } else {
        String::from_utf8_lossy(&bytes).into_owned()
    };
    Ok(HttpResponse { status, headers, body })
}

fn credential(key: &str) -> Result<keyring::Entry, String> {
    keyring::Entry::new(KEYRING_SERVICE, key).map_err(|error| error.to_string())
}

#[tauri::command]
async fn save_secret(key: String, value: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || credential(&key)?.set_password(&value).map_err(|error| error.to_string()))
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn load_secret(key: String) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || match credential(&key)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(error.to_string()),
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn delete_secret(key: String) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || match credential(&key)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(error) => Err(error.to_string()),
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn save_text_file(
    filename: String,
    content: String,
    mime_type: String,
) -> Result<SaveTextFileResponse, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let filename = if filename.trim().is_empty() {
            "export.txt".to_owned()
        } else {
            filename
        };
        let extension = Path::new(&filename)
            .extension()
            .and_then(|extension| extension.to_str())
            .filter(|extension| !extension.is_empty())
            .unwrap_or("txt");
        let filter_name = if mime_type.trim().is_empty() {
            "Text files".to_owned()
        } else {
            mime_type
        };
        let path = rfd::FileDialog::new()
            .set_file_name(&filename)
            .add_filter(&filter_name, &[extension])
            .save_file();

        match path {
            Some(path) => {
                std::fs::write(&path, content.as_bytes()).map_err(|error| error.to_string())?;
                Ok(SaveTextFileResponse {
                    saved: true,
                    path: Some(path.to_string_lossy().into_owned()),
                })
            }
            None => Ok(SaveTextFileResponse {
                saved: false,
                path: None,
            }),
        }
    })
    .await
    .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn speak_text(text: String, _locale: String, _rate: f32) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let script = "Add-Type -AssemblyName System.Speech; $voice = New-Object System.Speech.Synthesis.SpeechSynthesizer; $voice.Rate = -1; $voice.Speak($args[0])";
        let status = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script, &text])
            .status()
            .map_err(|error| error.to_string())?;
        if status.success() { Ok(()) } else { Err("Windows 语音朗读失败".to_owned()) }
    })
    .await
    .map_err(|error| error.to_string())?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            native_http_request,
            save_secret,
            load_secret,
            delete_secret,
            save_text_file,
            speak_text
        ])
        .run(tauri::generate_context!())
        .expect("failed to run English Word Review");
}
