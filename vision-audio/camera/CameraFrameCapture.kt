package com.secondlife.visionaudio.camera

// Rohan's sensor module — CameraX-based frame capture.
//
// Contract → Shravan:
//   android.graphics.Bitmap — 768×768 px, RGB (ARGB_8888 stripped to RGB)
//   Orientation-corrected and EXIF-stripped before delivery.
