# Keep the existing release bytecode layout and names, including device-test entry points.
# Only the unreachable on-device inference implementation is eligible for removal.
-dontoptimize
-dontobfuscate
-keep class !de.kf.tapstop.Qualcomm*,!de.kf.tapstop.ComposableSingletons$Qualcomm*,!de.kf.tapstop.AndroidQualcommHardware*,!de.kf.tapstop.ReflectiveVoiceAi*,!de.kf.tapstop.NativeWhisper*,!de.kf.tapstop.LocalInspection*,!de.kf.tapstop.LocalTranscript*,!de.kf.tapstop.SafModelSource*,!de.kf.tapstop.PcmAudioRecording*,!de.kf.tapstop.PcmWav*,** { *; }
