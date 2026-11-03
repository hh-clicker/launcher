#ifndef AppVersion
  #error AppVersion is required
#endif
#ifndef SourceDir
  #error SourceDir is required
#endif
#ifndef OutputDir
  #error OutputDir is required
#endif
#ifndef SupportDir
  #error SupportDir is required
#endif
#ifndef ReleaseNotesDir
  #error ReleaseNotesDir is required
#endif
#ifndef ReleaseNotesIndex
  #error ReleaseNotesIndex is required
#endif
#ifndef MinimumUpgradeVersion
  #define MinimumUpgradeVersion ""
#endif
#ifndef AppDisplayVersion
  #define AppDisplayVersion AppVersion
#endif
#ifndef OutputSuffix
  #define OutputSuffix ""
#endif

#define AppName "HH Clicker"
#define LauncherName "HH Clicker Launcher"

[Setup]
AppId={{A21DA3B8-FF2C-4160-90D8-7B4659C3B62C}
AppName={#AppName}
AppVersion={#AppDisplayVersion}
AppPublisher=HH Clicker
VersionInfoVersion={#AppVersion}
DefaultDirName={localappdata}\HH Clicker\application
DefaultGroupName=HH Clicker
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
Compression=lzma2/max
SolidCompression=yes
OutputDir={#OutputDir}
OutputBaseFilename=HH-Clicker-Setup-{#AppVersion}{#OutputSuffix}
SetupIconFile={#SupportDir}\..\desktopApp\src\main\resources\hh-clicker.ico
UninstallDisplayIcon={app}\HH Clicker Launcher.exe
CloseApplications=yes
RestartApplications=no
UsePreviousAppDir=yes
WizardStyle=modern

[Languages]
Name: "russian"; MessagesFile: "compiler:Languages\Russian.isl"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "{#SupportDir}\cleanup-docker.ps1"; DestDir: "{app}\uninstall-support"; Flags: ignoreversion
Source: "{#SupportDir}\update-files.ps1"; Flags: dontcopy
Source: "{#ReleaseNotesIndex}"; Flags: dontcopy
Source: "{#ReleaseNotesDir}\*.ru.md"; Flags: dontcopy

[InstallDelete]
Type: filesandordirs; Name: "{app}\*"; Check: IsUpdateInstall

[Dirs]
Name: "{localappdata}\HH Clicker\data"; Flags: uninsneveruninstall

[Icons]
Name: "{autoprograms}\HH Clicker\HH Clicker"; Filename: "{app}\HH Clicker Launcher.exe"; WorkingDir: "{app}"
Name: "{autodesktop}\HH Clicker"; Filename: "{app}\HH Clicker Launcher.exe"; WorkingDir: "{app}"

[Run]
Filename: "{app}\HH Clicker Launcher.exe"; Description: "Запустить HH Clicker"; Flags: nowait postinstall skipifsilent

[Code]
var
  FullRemoval: Boolean;
  RemovalForm: TSetupForm;
  FullRemovalCheck: TNewCheckBox;
  UpdateInstall: Boolean;
  UpdatePrepared: Boolean;
  UpdateCommitted: Boolean;
  UpdateRolledBack: Boolean;
  InstalledVersion: String;

function ProductApplicationRoot(): String;
begin
  Result := ExpandConstant('{localappdata}\HH Clicker\application');
end;

function ProductDataRoot(): String;
begin
  Result := ExpandConstant('{localappdata}\HH Clicker\data');
end;

function PendingApplicationBackupRoot(): String;
begin
  Result := ProductDataRoot() + '\updates\active\application-backup';
end;

function ReadBundleVersion(const ApplicationRoot: String): String;
var
  Lines: TArrayOfString;
  Index: Integer;
  Line: String;
begin
  Result := '';
  if not LoadStringsFromFile(ApplicationRoot + '\app\resources\.hh-clicker-bundle', Lines) then
    exit;
  for Index := 0 to GetArrayLength(Lines) - 1 do begin
    Line := Trim(Lines[Index]);
    if Pos('version=', Line) = 1 then begin
      Result := Trim(Copy(Line, 9, Length(Line)));
      exit;
    end;
  end;
end;

function ParseVersion(const Version: String; var Major, Minor, Patch: Integer): Boolean;
var
  FirstDot: Integer;
  SecondDot: Integer;
  Tail: String;
  MajorText: String;
  MinorText: String;
  PatchText: String;
begin
  Result := False;
  FirstDot := Pos('.', Version);
  if FirstDot <= 1 then exit;
  Tail := Copy(Version, FirstDot + 1, Length(Version));
  SecondDot := Pos('.', Tail);
  if (SecondDot <= 1) or (Pos('.', Copy(Tail, SecondDot + 1, Length(Tail))) > 0) then exit;
  MajorText := Copy(Version, 1, FirstDot - 1);
  MinorText := Copy(Tail, 1, SecondDot - 1);
  PatchText := Copy(Tail, SecondDot + 1, Length(Tail));
  if PatchText = '' then exit;
  try
    Major := StrToInt(MajorText);
    Minor := StrToInt(MinorText);
    Patch := StrToInt(PatchText);
    Result := (Major >= 0) and (Minor >= 0) and (Patch >= 0);
  except
    Result := False;
  end;
end;

function CompareVersions(const LeftVersion, RightVersion: String): Integer;
var
  LeftMajor, LeftMinor, LeftPatch: Integer;
  RightMajor, RightMinor, RightPatch: Integer;
begin
  if not ParseVersion(LeftVersion, LeftMajor, LeftMinor, LeftPatch) then
    RaiseException('Некорректная версия: ' + LeftVersion);
  if not ParseVersion(RightVersion, RightMajor, RightMinor, RightPatch) then
    RaiseException('Некорректная версия: ' + RightVersion);
  if LeftMajor <> RightMajor then begin
    if LeftMajor < RightMajor then Result := -1 else Result := 1;
  end else if LeftMinor <> RightMinor then begin
    if LeftMinor < RightMinor then Result := -1 else Result := 1;
  end else if LeftPatch <> RightPatch then begin
    if LeftPatch < RightPatch then Result := -1 else Result := 1;
  end else
    Result := 0;
end;

function IsUpdateInstall(): Boolean;
begin
  Result := UpdateInstall;
end;

function RunUpdateActionWithCode(const Action: String; var ResultCode: Integer): Boolean;
var
  PowerShell: String;
  Script: String;
  LogPath: String;
  Parameters: String;
begin
  ResultCode := -1;
  ExtractTemporaryFile('update-files.ps1');
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  Script := ExpandConstant('{tmp}\update-files.ps1');
  LogPath := ProductDataRoot() + '\updates\update.log';
  Parameters := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + Script +
    '" -Action ' + Action +
    ' -ApplicationRoot "' + ProductApplicationRoot() +
    '" -DataRoot "' + ProductDataRoot() +
    '" -CurrentVersion "' + InstalledVersion +
    '" -TargetVersion "{#AppVersion}"' +
    ' -LogPath "' + LogPath + '"';
  Result := Exec(PowerShell, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and
    (ResultCode = 0);
end;

function RunUpdateAction(const Action: String): Boolean;
var
  ResultCode: Integer;
begin
  Result := RunUpdateActionWithCode(Action, ResultCode);
end;

function DockerUpdateMessage(const ResultCode: Integer): String;
begin
  if ResultCode = 21 then
    Result := 'Обнаружена установленная версия HH Clicker ' + InstalledVersion + '. ' +
      'Для безопасного обновления нужен Docker Desktop, но Docker CLI не найден. ' +
      'Проверьте установку Docker Desktop.'
  else if ResultCode = 22 then
    Result := 'Обнаружена установленная версия HH Clicker ' + InstalledVersion + '. ' +
      'Для безопасного обновления запустите Docker Desktop и дождитесь запуска Docker Engine.'
  else
    Result := '';
end;

function InitializeSetup(): Boolean;
var
  ApplicationRoot: String;
  MinimumVersion: String;
begin
  Result := False;
  ApplicationRoot := ProductApplicationRoot();
  UpdateInstall := FileExists(ApplicationRoot + '\HH Clicker Launcher.exe') or
    FileExists(ApplicationRoot + '\app\resources\.hh-clicker-bundle') or
    FileExists(PendingApplicationBackupRoot() + '\app\resources\.hh-clicker-bundle');
  UpdatePrepared := False;
  UpdateCommitted := False;
  UpdateRolledBack := False;
  InstalledVersion := '';
  if not UpdateInstall then begin
    Result := True;
    exit;
  end;

  InstalledVersion := ReadBundleVersion(ApplicationRoot);
  if InstalledVersion = '' then
    InstalledVersion := ReadBundleVersion(PendingApplicationBackupRoot());
  if InstalledVersion = '' then begin
    MsgBox('Установленная копия HH Clicker не содержит сведений о версии. ' +
      'Обновление остановлено, существующие файлы не изменены.', mbError, MB_OK);
    exit;
  end;
  try
    if CompareVersions('{#AppVersion}', InstalledVersion) < 0 then begin
      MsgBox('Переход с HH Clicker ' + InstalledVersion + ' на более старую версию {#AppVersion} запрещён.',
        mbError, MB_OK);
      exit;
    end;
    MinimumVersion := '{#MinimumUpgradeVersion}';
    if (MinimumVersion <> '') and (CompareVersions(InstalledVersion, MinimumVersion) < 0) then begin
      MsgBox('Для обновления до HH Clicker {#AppVersion} сначала требуется версия ' + MinimumVersion + '.',
        mbError, MB_OK);
      exit;
    end;
  except
    MsgBox('Не удалось сравнить установленную и новую версии HH Clicker. ' +
      'Обновление остановлено, существующие файлы не изменены.', mbError, MB_OK);
    exit;
  end;
  Result := True;
end;

procedure InitializeWizard();
var
  NotesBytes: AnsiString;
  Notes: String;
  VersionNotes: String;
  NotesVersions: TArrayOfString;
  NotesVersion: String;
  NotesFileName: String;
  Index: Integer;
  NotesPage: TWizardPage;
  NotesIntro: TNewStaticText;
  NotesMemo: TNewMemo;
begin
  if not UpdateInstall then exit;
  if CompareVersions('{#AppVersion}', InstalledVersion) <= 0 then exit;
  ExtractTemporaryFile('release-notes.index');
  if not LoadStringsFromFile(ExpandConstant('{tmp}\release-notes.index'), NotesVersions) then exit;
  Notes := '';
  for Index := 0 to GetArrayLength(NotesVersions) - 1 do begin
    NotesVersion := Trim(NotesVersions[Index]);
    if (NotesVersion <> '') and
      (CompareVersions(NotesVersion, InstalledVersion) > 0) and
      (CompareVersions(NotesVersion, '{#AppVersion}') <= 0) then begin
      NotesFileName := NotesVersion + '.ru.md';
      ExtractTemporaryFile(NotesFileName);
      if LoadStringFromFile(ExpandConstant('{tmp}\' + NotesFileName), NotesBytes) then begin
        VersionNotes := Utf8Decode(NotesBytes);
        { The installer memo displays plain text, while GitHub renders these notes as Markdown. }
        StringChangeEx(VersionNotes, '**', '', True);
        StringChangeEx(VersionNotes, '# ', '', True);
        StringChangeEx(VersionNotes, #13#10, #10, True);
        StringChangeEx(VersionNotes, #10, #13#10, True);
        if Notes <> '' then Notes := Notes + #13#10 + #13#10;
        Notes := Notes + Trim(VersionNotes);
      end;
    end;
  end;
  if Notes = '' then exit;
  NotesPage := CreateCustomPage(
    wpWelcome,
    'Обновление HH Clicker',
    'Версия ' + InstalledVersion + ' → {#AppVersion}'
  );
  NotesIntro := TNewStaticText.Create(NotesPage);
  NotesIntro.Parent := NotesPage.Surface;
  NotesIntro.Left := 0;
  NotesIntro.Top := 0;
  NotesIntro.Width := NotesPage.SurfaceWidth;
  NotesIntro.Caption := 'Перед установкой ознакомьтесь с изменениями.';
  NotesMemo := TNewMemo.Create(NotesPage);
  NotesMemo.Parent := NotesPage.Surface;
  NotesMemo.Left := 0;
  NotesMemo.Top := NotesIntro.Top + NotesIntro.Height + ScaleY(8);
  NotesMemo.Width := NotesPage.SurfaceWidth;
  NotesMemo.Height := NotesPage.SurfaceHeight - NotesMemo.Top;
  NotesMemo.ReadOnly := True;
  NotesMemo.ScrollBars := ssVertical;
  NotesMemo.WordWrap := True;
  NotesMemo.Text := Notes;
end;

function NextButtonClick(CurPageID: Integer): Boolean;
var
  ResultCode: Integer;
  MessageText: String;
begin
  Result := True;
  if (not UpdateInstall) or (CurPageID <> wpReady) then exit;
  if RunUpdateActionWithCode('CheckDocker', ResultCode) then exit;
  MessageText := DockerUpdateMessage(ResultCode);
  if MessageText = '' then
    MessageText := 'Не удалось проверить готовность Docker для обновления HH Clicker.';
  MsgBox(MessageText + #13#10 + #13#10 +
    'После этого снова нажмите «Установить».' + #13#10 + #13#10 +
    'Установленная версия не изменена. Диагностика: ' + ProductDataRoot() + '\updates\update.log',
    mbError, MB_OK);
  Result := False;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
  DockerMessage: String;
begin
  Result := '';
  if not UpdateInstall then exit;
  if RunUpdateAction('Prepare') then begin
    UpdatePrepared := True;
  end else begin
    DockerMessage := '';
    if not RunUpdateActionWithCode('CheckDocker', ResultCode) then
      DockerMessage := DockerUpdateMessage(ResultCode);
    if DockerMessage <> '' then
      Result := DockerMessage
    else
      Result := 'Не удалось подготовить HH Clicker к обновлению. ' +
        'Если лаунчер запущен, закройте его и повторите установку.';
    Result := Result + #13#10 + #13#10 +
      'После устранения причины повторите установку.' + #13#10 + #13#10 +
      'Существующая версия не изменена. Диагностика: ' + ProductDataRoot() + '\updates\update.log';
  end;
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if (CurStep = ssPostInstall) and UpdatePrepared and (not UpdateCommitted) then begin
    if not RunUpdateAction('Commit') then begin
      UpdateRolledBack := RunUpdateAction('Rollback');
      if UpdateRolledBack then
        MsgBox('Новая версия не прошла проверку установленных файлов. Предыдущая версия восстановлена.' + #13#10 + #13#10 +
          'Диагностика: ' + ProductDataRoot() + '\updates\update.log', mbError, MB_OK)
      else
        MsgBox('Новая версия не прошла проверку, и автоматически восстановить предыдущую версию не удалось.' + #13#10 + #13#10 +
          'Не удаляйте данные. Диагностика: ' + ProductDataRoot() + '\updates\update.log',
          mbError, MB_OK);
      Abort;
    end;
    UpdateCommitted := True;
  end;
  if (CurStep = ssDone) and UpdateCommitted then
    RunUpdateAction('Cleanup');
end;

procedure DeinitializeSetup();
begin
  if UpdatePrepared and (not UpdateCommitted) and (not UpdateRolledBack) then
    RunUpdateAction('Rollback');
end;

function RunDockerCleanup(): Boolean;
var
  ResultCode: Integer;
  PowerShell: String;
  Script: String;
  LogPath: String;
  Parameters: String;
begin
  PowerShell := ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe');
  Script := ExpandConstant('{app}\uninstall-support\cleanup-docker.ps1');
  LogPath := ExpandConstant('{localappdata}\HH Clicker\uninstall.log');
  Parameters := '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + Script +
    '" -ApplicationRoot "' + ExpandConstant('{app}') +
    '" -DataRoot "' + ExpandConstant('{localappdata}\HH Clicker\data') +
    '" -LogPath "' + LogPath + '"';
  Result := Exec(PowerShell, Parameters, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and
    (ResultCode = 0);
end;

function ChooseRemovalOptions(): Boolean;
var
  Heading: TNewStaticText;
  Explanation: TNewStaticText;
  RemoveButton: TNewButton;
  CancelButton: TNewButton;
begin
  RemovalForm := CreateCustomForm(560, 235, True, True);
  try
    RemovalForm.Caption := 'Удаление HH Clicker';
    RemovalForm.Position := poScreenCenter;

    Heading := TNewStaticText.Create(RemovalForm);
    Heading.Parent := RemovalForm;
    Heading.Left := ScaleX(24);
    Heading.Top := ScaleY(20);
    Heading.Width := ScaleX(510);
    Heading.Height := ScaleY(24);
    Heading.AutoSize := False;
    Heading.Caption := 'Удаление HH Clicker';
    Heading.Font.Style := [fsBold];
    Heading.Font.Size := 12;

    Explanation := TNewStaticText.Create(RemovalForm);
    Explanation.Parent := RemovalForm;
    Explanation.Left := ScaleX(24);
    Explanation.Top := ScaleY(52);
    Explanation.Width := ScaleX(510);
    Explanation.Height := ScaleY(54);
    Explanation.AutoSize := False;
    Explanation.WordWrap := True;
    Explanation.Caption := 'По умолчанию удаляется только программа, а ваши данные сохраняются.';

    FullRemovalCheck := TNewCheckBox.Create(RemovalForm);
    FullRemovalCheck.Parent := RemovalForm;
    FullRemovalCheck.Left := ScaleX(28);
    FullRemovalCheck.Top := ScaleY(112);
    FullRemovalCheck.Width := ScaleX(500);
    FullRemovalCheck.Height := ScaleY(22);
    FullRemovalCheck.Caption := 'Полное удаление';

    Explanation := TNewStaticText.Create(RemovalForm);
    Explanation.Parent := RemovalForm;
    Explanation.Left := ScaleX(50);
    Explanation.Top := ScaleY(138);
    Explanation.Width := ScaleX(480);
    Explanation.Height := ScaleY(40);
    Explanation.AutoSize := False;
    Explanation.WordWrap := True;
    Explanation.Font.Color := clGray;
    Explanation.Caption := 'Удалить профили браузера, настройки, кеш, portable Node и Docker-контейнеры, volumes, сети и локальные образы HH Clicker.';

    RemoveButton := TNewButton.Create(RemovalForm);
    RemoveButton.Parent := RemovalForm;
    RemoveButton.Left := ScaleX(350);
    RemoveButton.Top := ScaleY(190);
    RemoveButton.Width := ScaleX(90);
    RemoveButton.Height := ScaleY(28);
    RemoveButton.Caption := 'Удалить';
    RemoveButton.ModalResult := mrOk;
    RemoveButton.Default := True;

    CancelButton := TNewButton.Create(RemovalForm);
    CancelButton.Parent := RemovalForm;
    CancelButton.Left := ScaleX(450);
    CancelButton.Top := ScaleY(190);
    CancelButton.Width := ScaleX(86);
    CancelButton.Height := ScaleY(28);
    CancelButton.Caption := 'Отмена';
    CancelButton.ModalResult := mrCancel;
    CancelButton.Cancel := True;

    Result := RemovalForm.ShowModal() = mrOk;
    if Result then
      FullRemoval := FullRemovalCheck.Checked;
  finally
    RemovalForm.Free();
  end;
end;

function InitializeUninstall(): Boolean;
var
  Choice: Integer;
  LogPath: String;
begin
  Result := ChooseRemovalOptions();
  if not Result then begin
    Abort;
  end;

  if FullRemoval then begin
    LogPath := ExpandConstant('{localappdata}\HH Clicker\uninstall.log');
    while not RunDockerCleanup() do begin
      Choice := MsgBox(
        'Не удалось удалить Docker-сущности HH Clicker.' + #13#10 + #13#10 +
        'Убедитесь, что Docker Desktop запущен, и нажмите «Повторить».' + #13#10 +
        '«Пропустить» удалит только программу и сохранит пользовательские данные.' + #13#10 + #13#10 +
        'Диагностика: ' + LogPath,
        mbError, MB_ABORTRETRYIGNORE);
      if Choice = IDIGNORE then begin
        FullRemoval := False;
        break;
      end;
      if Choice <> IDRETRY then begin
        Abort;
      end;
    end;
  end;
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  DataRoot: String;
  DeleteTarget: String;
begin
  if CurUninstallStep = usPostUninstall then begin
    DataRoot := ExpandConstant('{localappdata}\HH Clicker\data');
    if FullRemoval then
      DeleteTarget := DataRoot
    else
      DeleteTarget := '';

    if (DeleteTarget <> '') and (not DelTree(DeleteTarget, True, True, True)) then
      MsgBox('Некоторые пользовательские данные удалить не удалось: ' + DeleteTarget,
        mbError, MB_OK);

    if FullRemoval then begin
      DeleteFile(ExpandConstant('{localappdata}\HH Clicker\uninstall.log'));
      RemoveDir(ExpandConstant('{localappdata}\HH Clicker'));
    end;
  end;
end;
