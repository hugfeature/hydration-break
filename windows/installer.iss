[Setup]
AppId={{9F2FDEB1-B971-4F4C-9707-052C41B4E183}
AppName=休息与运动
AppVersion=0.2.0
AppPublisher=RestAndMove
DefaultDirName={localappdata}\Programs\RestAndMove
DefaultGroupName=休息与运动
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\dist
OutputBaseFilename=RestAndMove-0.2.0-windows-x64-setup
UninstallDisplayIcon={app}\RestAndMove.exe
SetupIconFile=app.ico
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
[Files]
Source: "..\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
[Icons]
Name: "{group}\休息与运动"; Filename: "{app}\RestAndMove.exe"
Name: "{group}\卸载休息与运动"; Filename: "{uninstallexe}"
[Run]
Filename: "{app}\RestAndMove.exe"; Description: "启动休息与运动"; Flags: nowait postinstall skipifsilent
