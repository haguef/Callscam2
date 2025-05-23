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

### Google Cloud Credentials

⚠️ **IMPORTANT: Security Notice** ⚠️

The app requires Google Cloud Speech-to-Text credentials to function. These credentials must be kept private and should never be committed to version control.

1. Create a service account in Google Cloud Console:
   - Go to [Google Cloud Console](https://console.cloud.google.com)
   - Create a new project or select an existing one
   - Enable the Speech-to-Text API
   - Create a service account
   - Download the JSON credentials file

2. Place the credentials:
   - Save the downloaded JSON file as `credentials.json`
   - Place it in `app/src/main/assets/credentials.json`
   - This file is gitignored and should never be committed

### Development Setup

1. Clone the repository
2. Place your `credentials.json` file as described above
3. Open the project in Android Studio
4. Build and run

### CI/CD Setup

For CI/CD environments, do NOT commit the credentials file. Instead:
1. Store the credentials content as an encrypted environment variable
2. During build, decode the credentials into the correct location
3. Ensure the credentials file is excluded from any artifacts

## Security Best Practices

- Never commit credentials to version control
- Keep your service account credentials private
- Regularly rotate your service account keys
- Use the principle of least privilege for service accounts
- Monitor your Google Cloud Console for any unauthorized usage

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