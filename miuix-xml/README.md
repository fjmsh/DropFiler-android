> **Vendored copy in DropFiler.** This directory is an ordinary, Git-tracked copy of
> [`fjmsh/miuix-xml`](https://github.com/fjmsh/miuix-xml) at commit
> `c7af21c3d6c122a87b96bf0a1b740ed9ad8e0a92`, itself a fork of
> [HChenX/HiMiuix](https://github.com/HChenX/HiMiuix). In DropFiler it is built as the local
> Gradle module `:HiMiuix` (`settings.gradle` maps it to `miuix-xml/`); it is **not** a git
> submodule and no separate checkout is needed. The upstream instructions below describe how
> the original library is consumed elsewhere. The library remains licensed under LGPL-2.1
> (see [LICENSE](LICENSE)).

<div align="center">
<h1>HiMiuix</h1>

![stars](https://img.shields.io/github/stars/HChenX/HiMiuix?style=flat)
![Github repo size](https://img.shields.io/github/repo-size/HChenX/HiMiuix)
![last commit](https://img.shields.io/github/last-commit/HChenX/HiMiuix?style=flat)
![language](https://img.shields.io/badge/language-java-purple)

[//]: # (<p><b><a href="README-en.md">English</a> | <a href="README.md">简体中文</a></b></p>)
<p>仿 Miuix 的 xml 式组件</p>
</div>

### HiMiuiX

#### Demo 项目:

- [HiMiuixDemo](https://github.com/HChenX/HiMiuixDemo)
- 欢迎下载体验！

#### 展示:

- Miuix About
  ![MiuixAbout](https://raw.githubusercontent.com/HChenX/HiMiuix/master/image/miuix.jpg)

### 使用

- 请做为模块在项目中导入使用！

```shell
  # 在你仓库中执行，将本仓库作为模块使用
  git submodule add https://github.com/HChenX/HiMiuix.git
  # 后续拉取本模块仓库
  git submodule update --init
```

- 然后设置项目 settings.gradle 添加:

```groovy
include ':HiMiuix'
```

- 最后设置项目 app 下 build.gradle 文件，添加:

```groovy
implementation(project(':HiMiuix'))
```

- tip: 请确保导入并使用了 `com.android.library`

#### 开源许可

- 本项目遵循 LGPL-2.1 开源协议。
- 使用本项目请在项目中注明！

---

## Changes on this branch (English)

This fork is used by [File Saver Android](https://github.com/fjmd3v/file-saver-android),
which targets older Android versions. Changes:

* **minSdk 29 → 21.** An audit with Android lint (`NewApi`, `InlinedApi`) at minSdk 21
  showed the library did not actually need API 29. The few newer calls now use
  compat helpers or version checks (`ContextCompat.getColor`, int encoding instead of
  `Parcel#readBoolean`, Application lifecycle callbacks below API 29, …), and core library
  desugaring is enabled for `java.util.stream`. Consumers must also enable
  `coreLibraryDesugaringEnabled true` and add `coreLibraryDesugaring
  'com.android.tools:desugar_jdk_libs:2.1.5'` when their minSdk is below 24.
  Limitation: below API 26 `MiuixSeekBar` cannot use a minimum other than 0.
* **Robustness:** `MiuixAppBar` no longer crashes when nested scrolling starts before a
  target is registered; `PropUtils` falls back to defaults when
  `android.os.SystemProperties` cannot be reflected (previously every dialog crashed).
* **Accessibility:** switch / checkbox / radio rows report their checked state and widget
  class to TalkBack.
* **Blur:** `MiuiSuperBlur.setBlurAllowed(false)` lets an app turn the Xiaomi blur off
  (components then use plain surface colours); `isBlurAvailable()` reports whether the
  methods exist at all.
* `WindowInsetsHelper` keeps the DecorView default inset handling.
* Dialog buttons are not all-caps.
* Drop-down / list dialog options respond to real finger taps (the whole row is
  clickable instead of forwarding touch events to the check box).

Licence unchanged: LGPL-2.1.
