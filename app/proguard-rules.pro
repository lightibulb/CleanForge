# Shizuku instantiates this class by name inside the privileged process.
-keep class com.cleanforge.core.shizuku.ShizukuFileService { public <init>(); }
-keep class com.cleanforge.shizuku.IFileService { *; }
-keep class com.cleanforge.shizuku.IFileService$Stub { *; }
