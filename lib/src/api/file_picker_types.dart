/// Type of file to pick.
enum FileType {
  /// Select any file.
  any,

  /// Select video and image files.
  media,

  /// Select image files.
  image,

  /// Select video files.
  video,

  /// Select audio files.
  audio,

  /// Select files with specific extensions.
  custom,
}

/// Status of the file picker.
enum FilePickerStatus {
  /// The file picker is currently active/open.
  picking,

  /// The file picking process is complete.
  done,
}

/// Status of an Android storage permission request.
///
/// On non-Android platforms all methods return [notApplicable].
enum StoragePermissionStatus {
  /// The permission has been granted.
  granted,

  /// The permission was denied.
  denied,

  /// The user selected "Don't ask again". The app must direct them to Settings
  /// manually — calling [FilePicker.requestStoragePermission] again will not
  /// show a system dialog.
  permanentlyDenied,

  /// Not applicable on this platform (iOS, web, desktop).
  notApplicable,
}

/// Media categories for the granular Android 13+ (API 33) storage permissions.
///
/// Pass a subset to [FilePicker.requestStoragePermission] to request only the
/// permission types your app actually needs.
enum AndroidMediaPermissionType {
  /// Corresponds to [READ_MEDIA_IMAGES].
  images,

  /// Corresponds to [READ_MEDIA_VIDEO].
  video,

  /// Corresponds to [READ_MEDIA_AUDIO].
  audio,
}
