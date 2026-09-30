# Keep the GL renderer callbacks; they are invoked reflectively-free but keep
# class names readable in crash logs.
-keepnames class pl.vovcia.softtempest.** { *; }
