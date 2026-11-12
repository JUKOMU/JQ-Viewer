; 单一 EXE 的语言引导层；安装和维护仍全部由 MSI 负责。
Unicode true
!include "MUI2.nsh"
!include "FileFunc.nsh"
!include "LogicLib.nsh"

Name "JQ Viewer"
OutFile "${OUTPUT_PATH}"
Icon "${ICON_PATH}"
RequestExecutionLevel user
SetCompressor /SOLID lzma
VIProductVersion "${INSTALLER_VERSION}.0"
VIAddVersionKey /LANG=1033 "ProductName" "JQ Viewer"
VIAddVersionKey /LANG=1033 "FileDescription" "JQ Viewer Installer"
VIAddVersionKey /LANG=1033 "FileVersion" "${INSTALLER_VERSION}"

!define MUI_LANGDLL_ALLLANGUAGES
!define MUI_LANGDLL_WINDOWTITLE "JQ Viewer"
!define MUI_LANGDLL_INFO "请选择安装语言 / Choose the installation language"
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"
!insertmacro MUI_LANGUAGE "SimpChinese"

LangString RestartRequired 1033 "Installation completed. Restart Windows to finish applying the update."
LangString RestartRequired 2052 "安装完成。请重启 Windows 以完成更新。"
LangString LaunchFailed 1033 "Unable to start Windows Installer."
LangString LaunchFailed 2052 "无法启动 Windows Installer。"
Var MsiArgs

Function .onInit
    ${GetParameters} $MsiArgs
    System::Call 'kernel32::GetUserDefaultUILanguage() i.r0'
    IntOp $0 $0 & 0x3ff
    ${If} $0 == 4
        StrCpy $LANGUAGE 2052
    ${Else}
        StrCpy $LANGUAGE 1033
    ${EndIf}
    SetErrorLevel 1602
    !insertmacro MUI_LANGDLL_DISPLAY
    InitPluginsDir
    SetOutPath "$PLUGINSDIR"
    File /oname=JQ-Viewer.msi "${MSI_PATH}"
    File /oname=zh-CN.mst "${MST_PATH}"
    StrCpy $1 ""
    ${If} $LANGUAGE == 2052
        StrCpy $1 'TRANSFORMS="$PLUGINSDIR\zh-CN.mst" TRANSFORMSSECURE=1'
    ${EndIf}
    ClearErrors
    ExecWait '"$SYSDIR\msiexec.exe" /i "$PLUGINSDIR\JQ-Viewer.msi" /norestart $1 $MsiArgs' $0
    ${If} ${Errors}
        MessageBox MB_OK|MB_ICONSTOP $(LaunchFailed)
        SetErrorLevel 1
    ${ElseIf} $0 == 3010
        MessageBox MB_OK|MB_ICONINFORMATION $(RestartRequired)
        SetErrorLevel 0
    ${Else}
        SetErrorLevel $0
    ${EndIf}
    Quit
FunctionEnd

; 安装在初始化中交给 msiexec，不显示第二套安装向导，也不注册 NSIS 卸载项。
Section
SectionEnd
