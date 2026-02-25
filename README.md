# Dual-Mode AI Chat Application

A modern, offline-first Android chat application that seamlessly combines the power of on-device LLMs and cloud-based Google Gemini AI. Built entirely with Jetpack Compose, this app serves as a robust proof-of-concept for hybrid AI architectures on mobile devices.

## Overview

This application provides a fluid chat interface that intelligently routes queries between a local, offline AI model and a powerful cloud API based on real-time network connectivity and user preference. 

### Key Features

*   **Hybrid AI Architecture:** Toggle instantly between a local on-device model (Gemma) and a cloud API (Gemini 2.5 Flash).
*   **Network-Aware Routing:** Actively monitors your internet connection. If you are in "Online Mode" and your network drops, the app automatically and transparently falls back to the offline local model to ensure uninterrupted service.
*   **On-Device Processing:** Runs a quantized Gemma 1.1 2B INT4 model completely offline using Google's MediaPipe Tasks GenAI pipeline.
*   **Automated Background Downloading:** First-time users are greeted with a WorkManager-powered background download of the 1.5GB local model, ensuring the app remains fully functional and responsive during the setup phase.
*   **Real-Time Streaming:** Responses from both the local and cloud models are streamed token-by-token back to the UI, providing a fast, ChatGPT-like experience.
*   **Secure Credential Management:** The user's Gemini API key is securely stored locally using Jetpack DataStore Preferences.
*   **Modern Android UI:** Built 100% with Jetpack Compose Material 3, featuring a dark theme, auto-scrolling message lists, and dynamic thinking indicators.

## Technical Stack

*   **Language:** Kotlin
*   **UI Framework:** Jetpack Compose (Material 3)
*   **Local AI Inference:** MediaPipe Tasks GenAI (`com.google.mediapipe:tasks-genai`)
*   **Background Tasks:** WorkManager for robust, resilient model downloading.
*   **Local Storage:** Jetpack DataStore Preferences for async, type-safe key-value storage.
*   **Network Requests:** Native `HttpURLConnection` for lightweight, zero-dependency REST queries to the Gemini API.
*   **Architecture:** MVVM (Model-View-ViewModel) using Coroutines and `StateFlow` for reactive UI updates.

## How It Works

1.  **Initialization:** On initial launch, `ModelDownloadWorker` downloads the `.bin` model file. If the network is unavailable or the user skips, they default to Cloud Mode.
2.  **State Management:** `ChatViewModel` maintains the conversation history within a sliding context window to prevent token overflow.
3.  **Routing:** When a prompt is submitted, `RoutingManager` checks the `isOnlineMode` toggle and the `NetworkMonitor` capabilities.
    *   If Online AND Connected: Routes to Gemini API via HTTP POST. Catches timeouts/exceptions and falls back to local.
    *   If Offline OR Local Mode: Routes to MediaPipe `LlmEngine`.
4.  **Display:** The `ChatScreen` collects the emitted tokens and displays them using the `MessageBubble` composable, labeling the source as "via Cloud" or "via Local".

## Setup Instructions

1.  Clone this repository.  
2.  Open the project in Android Studio (Giraffe or newer recommended).
3.  Build and run the project on a physical Android device or emulator.
    *   *Note: Local LLM inference requires significant RAM and CPU resources. A physical device is strongly recommended for testing the MediaPipe implementation.*
4.  To use the Cloud mode, you will need a free Gemini API key from Google AI Studio. The app will prompt you for this key when you attempt to toggle Online Mode.
