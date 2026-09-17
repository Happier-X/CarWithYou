# server 是推到手机 /data/local/tmp 由 app_process 拉起的 dex，
# 不进 Play，也不作为独立应用安装，所以不混淆。
-keep class com.carwithyou.server.** { *; }
