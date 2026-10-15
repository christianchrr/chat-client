# Local AI Assistant

A Windows desktop chat app that runs an AI model on your computer. Your chats are saved on your
computer. You can also add information from public web pages by pasting their URLs.

## Start the app

### If you already have the app folder

Open the `LocalAIAssistant` folder and double-click `LocalAIAssistant.exe`.

**Keep the whole folder together.** The EXE needs the other files and folders next to it.

The first time the app opens, it downloads only the local AI software (Ollama) and scans your hardware. No model is downloaded until you choose one. This needs an internet
connection and several gigabytes of free disk space. Leave the setup window open until it finishes.
If setup fails, choose **Retry**. Later launches reuse the downloads.

### Build the app yourself

This is for someone who has downloaded the project source code. Building requires Windows and JDK
21. You do not need to install Ollama, Docker, or WSL2.

1. Open PowerShell in the project folder (the folder containing this `README.md`).
2. Run:

   ```powershell
   .\packaging\build-app.ps1
   ```

3. When the build finishes, start the app:

   ```powershell
   & ".\product-launcher\target\package-0.1.39\LocalAIAssistant\LocalAIAssistant.exe"
   ```

The build may take a few minutes. If PowerShell says it cannot find `jpackage`, install JDK 21 and
try again. The app is created in
`product-launcher\target\package-0.1.39\LocalAIAssistant`. Keep that entire folder together.

### Make an installer to share

The build also creates `LocalAIAssistant-0.1.39.exe` and `.msi` installers next to the app folder. They
install to `C:\Program Files\LocalAIAssistantApp` (64-bit app; Windows asks for administrator approval), add a Start Menu entry and an optional desktop shortcut,
and appear under Windows **Settings > Apps** so they can be uninstalled. Uninstalling removes the program and also deletes all chats, models and Dropbox files in
`%LOCALAPPDATA%\LocalAIAssistant`; close the app first. Upgrading to a newer installer keeps them. Send the `.exe`
to another Windows PC. Building installers needs the free WiX Toolset 3.x (`winget install
WiXToolset.WiXToolset`, or extract `wix314-binaries.zip` to
`%LOCALAPPDATA%\LocalAIAssistant-build-tools\wix3`). Use `.\packaging\build-app.ps1 -Type app-image` to
skip installers. The installers are not code-signed, so Windows SmartScreen shows "unknown publisher"
(More info > Run anyway).

## Use the app

- **Chat:** type a message or prompt at the bottom and press Enter or choose **Send**. The reply
  streams into the conversation. The first response can take longer while a large model loads;
  the status shows elapsed wait time. The assistant is instructed to answer fully in concise,
  easy-to-understand language; ask for more detail when you want a longer explanation.
- **Conversations:** the list uses the opening message as a title (up to eight words). Choose
  **New chat** to start another conversation or **Delete chat** to permanently remove the selected
  conversation and its messages. An active conversation cannot be deleted until its response ends.
- **Add web information:** choose **Add web context**, paste up to five public links (one per line), and choose **Fetch and add**. Web pages, PDFs
  (first 50 pages), Word `.docx` files, and text files (txt, md, csv, json, xml, yaml) are
  supported, up to 10 MB each. The extracted text is added to the current chat (long documents are
  truncated to fit the model); then ask the assistant about it.
- **Dropbox (local files):** choose **Dropbox**, then drag files in or use **Add files**. Files live in
  `%LOCALAPPDATA%\LocalAIAssistant\dropbox`. Then just mention a file by name in the main chat (for example "summarize budget.xlsx" or "fix the typos in notes.txt") and the AI reads it automatically; edit requests save an edited copy. The window stays in sync if you add or delete files in File Explorer. **Add to chat** attaches a file manually, and **Edit with AI** starts an edit from the window. Supported types: txt, md,
  csv, json, xml, yaml, log, docx, xlsx, pdf, and images (png, jpg, webp, gif), and similar.
  Originals are never overwritten: the edited version is saved as `name-edited.ext`, then
  `name-edited-2.ext`, and so on. PDFs are read-only. Excel `.xlsx` workbooks (all sheets, first 2,000 rows each) are shown as tab-separated rows and can be edited: the AI returns the changed cells, and they are applied to a copy of the original workbook, so styles, widths and untouched formulas are kept (formulas you ask for are written as formulas and recalculated when opened in Excel). Images are read-only and need a vision model: choose **Qwen2.5-VL 7B (vision)** or **Llama 3.2 Vision 11B (vision)** in the model list, then mention the image by name (up to three images per message; Add to chat also works). If your `models.yml` was created by an older version, add those two entries (see the bundled example) to see them. Word `.docx` files are shown to the AI as Markdown (headings, bold/italic, bullets, tables, centered lines), so it can restyle them (for example a resume). The edited copy is regenerated as a cleanly styled Word file; original fonts, colors, images and exact layout are not carried over. Files over about 12,000 characters are too long to edit in one pass.
- **Conversation memory:** optionally create and manage a summary of older messages.
- **Choose a model:** use the **Model** dropdown. It shows the hardware advisory and disk space
  estimate for each option. A downloaded model shows an **Installed** badge and is used for chat right away. For a model you have not downloaded, a **Download** button appears next to the dropdown; clicking it asks for confirmation, then downloads the model and uses it for chat. Your
  choice is remembered next time.
- **Model library:** choose this to see every model configured in `models.yml` and whether it is
  downloaded. Choose **Download** to install one without switching models, **Use** to select an
  installed model for chat, or **Uninstall** to remove a downloaded model. Downloads show Ollama's
  current phase and byte progress. The installed section
  also lists models on this computer that are not in the configuration. The library displays the
  exact local model storage path. You may uninstall the selected model; if you remove it, chat is
  disabled until you select another model, and that empty selection is remembered across restarts.

The Model library shows the model folder (with an **Open folder** button), how much space models use and how much is free. A running download can be cancelled; downloading again resumes it. Replies run with thinking off by default and a fixed 8,192-token context so the model loads once and stays fully in GPU memory; models that can reason (such as Qwen3) show a **Thinking** checkbox next to the model list; turn it on for hard problems, at the cost of a slower start (your choice is remembered). The status bar shows the time to first word and tokens per second after each reply. **Help** in the tool row opens the logs folder or copies diagnostics (OS, Java, models, recent log lines) to the clipboard; review them before sharing. **Help > Test model speed** measures tokens per second. Each model shows a Fast/Okay/Slow estimate for this PC, and the first-run welcome card offers a one-click download of the recommended model (the best fit for your RAM, GPU and disk; vision models are never the default). After a first download finishes, a quick speed test result appears in the chat.

The editable model allow-list is created on first launch at
`%LOCALAPPDATA%\LocalAIAssistant\config\models.yml`. To add a model, add another entry under
`models:` with its Ollama `id`, a `displayName`, and integer GiB estimates for `estimatedDownloadGb`,
`recommendedVramGb`, and `recommendedRamGb`. Save the file and restart the app for changes to take
effect. The app validates the YAML before using it; invalid entries prevent startup. Correct the YAML
or restore the bundled example before restarting.

Web pages are retrieved only after you choose **Fetch and add**. Their contents are treated as
untrusted reference material, not instructions. The app does not search Google for you. It cannot
fetch pages on your computer or private network.

## Downloads, privacy, and saved data

On first launch, setup downloads the Ollama runtime only. The app then opens with no model selected and recommends the model best suited to the computer.
The starter `models.yml` offers Llama 3.2 3B (about 2 GB) and Qwen3 14B (about 10 GB), 30B (about
20 GB), and 32B (about 21 GB); actual download sizes vary. The app checks system memory, GPU (NVIDIA, AMD, or Intel)
memory when available, and free disk space to choose a default and show warnings. On a 24 GB RTX
4090 with 64 GB of RAM, it recommends Qwen3 30B. Selecting another model downloads it once; installed
models are reused. Setup verifies the runtime download before installing it and shows download
progress. Models already present on the computer are not deleted when you change the selected model.

Chat runs on your computer. The app contacts the internet during first-time downloads and when you
ask it to fetch web pages. Web-page text is saved in that conversation and sent to the local model.
Conversation data is stored in:

```text
%LOCALAPPDATA%\LocalAIAssistant\data
```

Downloaded model files are stored in
`%LOCALAPPDATA%\LocalAIAssistant\models\ollama`. The app checks whether the selected model is
already installed at startup and reuses it; it does not download the model again on every launch.
Switching to a model that is not installed downloads it once. **Uninstall** removes that model's
local files.

Closing the app stops the local model process started by the app. Your conversations and downloaded
models remain on disk for next time.

## What this version does not do

- It is an app folder, **not a signed installer**. There is no published one-file installer yet.
- Window layouts for Chrome, IntelliJ, and PowerShell cannot yet be saved or reapplied.
- Google search is not built in; add individual page URLs instead.
- Only models in `models.yml` can be selected. The starter list contains Qwen3 models; you can add
  other Ollama models to the YAML allow-list.

## If something goes wrong

- **Setup is slow:** the runtime and model are large downloads. Keep the setup window open and check
  your internet connection and free disk space.
- **Setup failed:** choose **Retry** in the setup window.
- **A server error appears or chat does not respond:** close and reopen the app. If the problem
  continues, send the contents of
  `%LOCALAPPDATA%\LocalAIAssistant\logs\application.log` to the person helping you. The log can
  contain chat content, so review it before sharing.
- **Need to start over:** downloaded files and chats are kept under
  `%LOCALAPPDATA%\LocalAIAssistant`. Removing that folder deletes those downloads and saved chats.

## For developers

Run the automated checks from the project folder:

```powershell
.\mvnw.cmd -B verify
```
