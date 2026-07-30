# Vendored java-fav SDK

此目录保存项目使用的本地 Bilibili 收藏夹 SDK：

```text
com.bili.fav:java-fav:1.0.0
```

源码来自本机 `D:\杂乱目录\java-fav`。原项目可通过以下命令安装进本地 Maven
仓库：

```powershell
.\mvnw.cmd -f "D:\杂乱目录\java-fav\pom.xml" clean install
```

为保证 CI、Docker 和新克隆的仓库不依赖某台机器预先存在的 `.m2`，项目同时把
原始 JAR 与 POM 放入 `vendor/maven-repository`，根 `pom.xml` 通过只读 file
repository 引用它。

`java-fav-1.0.0.jar` 的 SHA-256：

```text
065268828B439E2501BBC57FAB6FF7161461B4A845E4651B380398DF06D7D347
```

此目录不包含 Bilibili Cookie、二维码 key 或任何账号信息。升级 SDK 时需重新执行
测试，并同步更新制品、版本号和哈希。
