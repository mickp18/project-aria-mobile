# Project Aria Mobile

A high-performance Android application integrating **LiteRT (TensorFlow Lite)** for YOLO object detection, **Google MLKit** for text recognition, **Vosk** for offline speech-to-text, and **OpenCV** for native image processing.

---

## Prerequisites

Before you clone the project, ensure your environment meets these specific requirements:

* **Android Studio:** Ladybug | 2024.2.1 or newer.
* **JDK:** Version 17 (Required for Gradle Toolchain).
* **Android SDK:** API Level 36 (Baklava).
* **NDK & CMake:** 1. Open Android Studio > `Settings` > `Languages & Frameworks` > `Android SDK`.
    2. Select the **SDK Tools** tab.
    3. Check **NDK (Side by side)** and **CMake**.
    4. Click **Apply** to install.

---

## Getting Started

### 1. Clone the Repository
This project includes a local module for **OpenCV**. Ensure you clone recursively if submodules are used:

```bash
git clone [https://github.com/your-username/project-aria-mobile.git](https://github.com/your-username/project-aria-mobile.git)
cd project-aria-mobile
```
### 2. Assets Configuration
* The app requires pre-trained models to function. Please ensure the following files are present in src/main/assets/:

* YOLO Model: your_model.tflite

* Vosk Model: The vosk-model-android folder.

[!NOTE]
The build script is configured with noCompress.add("tflite") to ensure the models are mapped directly into memory for better performance.

### 3. Open & Sync
* Open Android Studio and select File > Open.

* Navigate to the project root and click OK.

* Android Studio will begin a Gradle Sync. This may take a while as it configures the C++ toolchain and downloads the LiteRT/Vosk dependencies.

## Building & Running
* Hardware Requirements
* Minimum SDK: 33 (Android 13)

* Target SDK: 36 (Android 16)

* Architecture: This project supports armeabi-v7a, arm64-v8a, x86, and x86_64.

## Execution
* Connect a physical device (recommended for GPU-accelerated LiteRT).
* Select the app configuration in the top toolbar.
* Check that in WebSocketViewModel the line  webSocketClient.setSocketUrl("ws://192.168.1.2:8080") in the connect() function contains the server's ip address
* Click the Run button (Green Play Icon).
[NOTE] The phone must be connected to same network as the server

## Project Structure
* src/main/cpp/: Contains CMakeLists.txt and native C++ source code.

* opencv/: The local OpenCV library module.

* libs.versions.toml: Centralized dependency management.

 
 ## Troubleshooting
1. OpenCV Module Not Found
If you see an error regarding :opencv, ensure the opencv directory is present in the root folder. If it was added as a git submodule, run:

Bash
git submodule update --init --recursive
2. CMake/NDK Errors
If the native build fails, go to File > Project Structure > SDK Location and verify the Android NDK location is correctly set.

3. App Crashes on Startup (Models)
Ensure your .tflite and Vosk model files are correctly named and placed in the assets folder. Check the Logcat for FileNotFoundException.

4. Gradle Version Issues
If you encounter Java version errors, go to Settings > Build, Execution, Deployment > Build Tools > Gradle and ensure the Gradle JDK is set to 17.
