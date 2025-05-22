# Scam Call Detector

An Android application that helps detect potential scam calls using real-time speech recognition and keyword detection.

## Features

- Real-time call transcription using Google Cloud Speech-to-Text
- Scam keyword detection
- Visual and vibration alerts for potential scams
- Notification system for warnings
- Permission handling for required Android permissions

## Requirements

- Android SDK 24 or higher
- Google Cloud Speech-to-Text API credentials
- Android device with call handling capabilities

## Setup

1. Clone the repository
2. Open the project in Android Studio
3. Add your Google Cloud credentials file to `app/src/main/res/raw/credentials.json`
4. Update the `applicationId` in `app/build.gradle` if needed
5. Build and run the application

## Required Permissions

The app requires the following permissions:
- `RECORD_AUDIO`: For call audio transcription
- `MODIFY_AUDIO_SETTINGS`: For audio handling
- `READ_PHONE_STATE`: For call state detection
- `ANSWER_PHONE_CALLS`: For call handling
- `INTERNET`: For Google Cloud API access
- `VIBRATE`: For warning notifications

## Configuration

The scam detection keywords can be modified in `CallDetectionService.kt`. Current keywords include:
- "urgent"
- "gift card"
- "transfer"
- "social security"
- "warranty"
- "irs"
- "tax"
- "fraud department"
- "microsoft support"
- "apple support"

## Building with Codemagic CI

The project includes a `codemagic.yaml` configuration file for CI/CD with Codemagic. The build process:
1. Sets up the build environment
2. Installs required tools
3. Configures Gradle
4. Builds the debug APK
5. Publishes the build artifacts

## License

This project is licensed under the MIT License - see the LICENSE file for details. 