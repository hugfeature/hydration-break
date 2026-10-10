using System.Reflection;
using System.Text.Json;
using Microsoft.Win32;

namespace RestAndMove;
static class Program {
    [STAThread]
    static int Main(string[] args) {
        if (args.Contains("--self-test")) return SelfTest.Run(args);
        using var mutex = new Mutex(true, "Local\\RestAndMove.Desktop", out bool first);
        if (!first) { MessageBox.Show("休息与运动已在托盘运行，请双击托盘图标打开。"); return 0; }
        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm());
        return 0;
    }
}
public sealed class MainForm : Form {
    const string Caution = "使用稳固无轮椅子，轻缓活动、自然呼吸，不适即停。有伤病或不确定时先咨询专业人员。";
    readonly SettingsStore store;
    Preferences prefs;
    readonly Move[] moves;
    readonly NotifyIcon tray;
    readonly System.Windows.Forms.Timer timer = new() { Interval = 1000 };
    readonly CheckBox water = new() { Text = "喝水提醒", AutoSize = true };
    readonly CheckBox exercise = new() { Text = "运动提醒", AutoSize = true };
    readonly NumericUpDown waterMinutes = Minutes();
    readonly NumericUpDown exerciseMinutes = Minutes();
    readonly NumericUpDown from = Hour();
    readonly NumericUpDown to = Hour();
    readonly Label status = new() { AutoSize = true, MaximumSize = new Size(560, 0), Padding = new Padding(0, 12, 0, 12) };
    readonly CheckedListBox choices = new() { Height = 155, Width = 540, CheckOnClick = true };
    DateTime lastTick = DateTime.Now;
    string? lastAction;
    bool exiting;
    public MainForm() {
        Text = "休息与运动"; Width = 640; Height = 810; MinimumSize = new Size(580, 650);
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Microsoft YaHei UI", 10);
        BackColor = Color.FromArgb(245, 248, 247);
        Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? SystemIcons.Information;
        store = new SettingsStore(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RestAndMove", "settings.json"));
        prefs = store.Load();
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("RestAndMove.exercises.json")!;
        moves = JsonSerializer.Deserialize<Move[]>(stream)!;
        Schedule.Rebuild(prefs, DateTime.Now, true);
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Padding = new Padding(24) };
        Controls.Add(panel);
        panel.Controls.Add(new Label { Text = "给忙碌留一点空隙", AutoSize = true, Font = new Font(Font.FontFamily, 21, FontStyle.Bold), ForeColor = Color.FromArgb(32,95,84) });
        panel.Controls.Add(new Label { Text = "离线运行 · 关闭窗口后留在托盘 · v0.2.0", AutoSize = true });
        panel.Controls.Add(status);
        water.Checked = prefs.Water; exercise.Checked = prefs.Exercise;
        waterMinutes.Value = prefs.WaterMinutes; exerciseMinutes.Value = prefs.ExerciseMinutes; from.Value = prefs.Start; to.Value = prefs.End;
        panel.Controls.Add(Row(water, waterMinutes, new Label { Text = "分钟", AutoSize = true }));
        panel.Controls.Add(Row(exercise, exerciseMinutes, new Label { Text = "分钟", AutoSize = true }));
        panel.Controls.Add(Row(new Label { Text = "每日时段", AutoSize = true }, from, new Label { Text = "至", AutoSize = true }, to, new Label { Text = "时（结束不含）", AutoSize = true }));
        panel.Controls.Add(new Label { Text = "通知中的动作（可全部取消，仅保留一般活动提醒）", AutoSize = true });
        foreach (var move in moves) choices.Items.Add(move.title, prefs.ExerciseIds == null || prefs.ExerciseIds.Contains(move.id));
        panel.Controls.Add(choices);
        panel.Controls.Add(Row(Button("查看动作", Preview), Button("保存设置", SaveControls)));
        panel.Controls.Add(Row(Button("暂停 30 分钟", () => Pause()), Button("恢复提醒", Resume), Button("设置与帮助", Help)));
        panel.Controls.Add(new Label { Text = "电脑睡眠时暂停，恢复后重新计时，不唤醒电脑。\n通知可见性受 Windows 勿扰和锁屏通知设置影响。\n关闭此窗口不会退出；请在托盘菜单中选择“退出”。", AutoSize = true, MaximumSize = new Size(540, 0) });
        var menu = new ContextMenuStrip();
        menu.Items.Add("打开", null, (_, _) => Open());
        menu.Items.Add("暂停 30 分钟", null, (_, _) => Pause());
        menu.Items.Add("恢复提醒", null, (_, _) => Resume());
        menu.Items.Add("退出", null, (_, _) => { exiting = true; Close(); });
        tray = new NotifyIcon { Icon = Icon, Text = "休息与运动", Visible = true, ContextMenuStrip = menu };
        tray.DoubleClick += (_, _) => Open();
        tray.BalloonTipClicked += (_, _) => { Open(); if (lastAction != null) MessageBox.Show(this, lastAction, "活动一下"); };
        timer.Tick += (_, _) => Tick(); timer.Start();
        SystemEvents.PowerModeChanged += PowerChanged;
        SystemEvents.TimeChanged += TimeChanged;
        FormClosing += (_, e) => { if (!exiting && e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); } };
        FormClosed += (_, _) => { timer.Stop(); tray.Dispose(); SystemEvents.PowerModeChanged -= PowerChanged; SystemEvents.TimeChanged -= TimeChanged; };
        Shown += (_, _) => { if (store.Warning != null) MessageBox.Show(this, store.Warning, "设置提示"); };
        RefreshStatus();
    }
    static NumericUpDown Minutes() => new() { Minimum = 15, Maximum = 240, Increment = 15, Width = 85 };
    static NumericUpDown Hour() => new() { Minimum = 0, Maximum = 23, Width = 60 };
    static FlowLayoutPanel Row(params Control[] controls) {
        var row = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 8, 0, 8) };
        row.Controls.AddRange(controls); return row;
    }
    static Button Button(string title, Action action) {
        var b = new Button { Text = title, AutoSize = true, MinimumSize = new Size(110, 38), FlatStyle = FlatStyle.Flat };
        b.Click += (_, _) => action(); return b;
    }
    void Persist() {
        try { store.Save(prefs); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) {
            timer.Stop(); prefs.LastResult = "保存失败，提醒已暂停；请检查磁盘和权限后重新保存。";
            MessageBox.Show(this, prefs.LastResult, "保存失败");
        }
    }
    void SaveControls() {
        if (from.Value >= to.Value) { MessageBox.Show(this, "开始时间必须早于结束时间。"); return; }
        bool windowChanged = prefs.Start != (int)from.Value || prefs.End != (int)to.Value;
        bool waterChanged = prefs.Water != water.Checked || prefs.WaterMinutes != (int)waterMinutes.Value;
        bool exerciseChanged = prefs.Exercise != exercise.Checked || prefs.ExerciseMinutes != (int)exerciseMinutes.Value;
        prefs.Water = water.Checked; prefs.Exercise = exercise.Checked;
        prefs.WaterMinutes = (int)waterMinutes.Value; prefs.ExerciseMinutes = (int)exerciseMinutes.Value;
        prefs.Start = (int)from.Value; prefs.End = (int)to.Value;
        prefs.ExerciseIds = choices.CheckedIndices.Cast<int>().Select(i => moves[i].id).ToArray();
        var now = DateTime.Now;
        if (windowChanged) Schedule.Rebuild(prefs, now);
        else {
            if (waterChanged) prefs.NextWater = prefs.Water ? Schedule.Next(now, prefs.WaterMinutes, prefs.Start, prefs.End) : null;
            if (exerciseChanged) prefs.NextExercise = prefs.Exercise ? Schedule.Next(now, prefs.ExerciseMinutes, prefs.Start, prefs.End) : null;
        }
        timer.Start(); Persist(); RefreshStatus();
    }
    void Preview() {
        int i = choices.SelectedIndex;
        if (i < 0) i = 0;
        MessageBox.Show(this, moves[i].steps + "\n\n" + Caution + "\n\n参考：" + moves[i].source, moves[i].title);
    }
    void Pause() { prefs.PausedUntil = DateTime.Now.AddMinutes(30); Persist(); RefreshStatus(); }
    void Resume() { prefs.PausedUntil = null; Schedule.Rebuild(prefs, DateTime.Now); Persist(); RefreshStatus(); }
    void Open() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    void Help() {
        using var dialog = new Form { Text = "设置与帮助", Width = 530, Height = 380, StartPosition = FormStartPosition.CenterParent };
        var panel = new FlowLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(20), FlowDirection = FlowDirection.TopDown };
        dialog.Controls.Add(panel);
        panel.Controls.Add(new Label { AutoSize = true, MaximumSize = new Size(460, 0), Text = "隐私：无联网、账号、遥测或自动上传；本地保存设置及最近提醒状态。\n不默认开机启动。升级保留设置；卸载默认保留设置，可手动删除 LocalAppData/RestAndMove。\n动作不是医疗处方。\n\n" + prefs.LastResult });
        panel.Controls.Add(Button("测试系统通知", () => tray.ShowBalloonTip(5000, "休息与运动 · 测试", "这只是通知测试，不会改变正式提醒计划。", ToolTipIcon.Info)));
        panel.Controls.Add(Button("Windows 通知设置", () => System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo("ms-settings:notifications") { UseShellExecute = true })));
        dialog.ShowDialog(this);
    }
    void RefreshStatus() {
        string Time(DateTime? d) => d?.ToString("MM-dd HH:mm:ss") ?? "已关闭";
        status.Text = prefs.PausedUntil > DateTime.Now ? "已暂停至 " + Time(prefs.PausedUntil) :
            "喝水 · " + Time(prefs.NextWater) + "\n运动 · " + Time(prefs.NextExercise);
    }
    void Tick() {
        var now = DateTime.Now;
        if (now - lastTick > TimeSpan.FromMinutes(2) || now < lastTick) { Schedule.Rebuild(prefs, now); Persist(); }
        lastTick = now;
        var due = Schedule.Tick(prefs, now);
        if (due.water || due.exercise) {
            var move = due.exercise ? Schedule.Pick(prefs, moves) : null;
            string body = (due.water ? "喝几口水，补充水分。" : "") +
                (due.exercise ? "\n" + (move == null ? "放下工作，换个舒适姿势。" : move.title + "：" + move.steps) : "");
            lastAction = body + "\n\n" + Caution;
            tray.ShowBalloonTip(10000, due.water && due.exercise ? "喝水，也活动一下" : due.water ? "喝点水吧" : "活动一下", body, ToolTipIcon.Info);
            prefs.LastResult = now.ToString("yyyy-MM-dd HH:mm:ss") + " 已提交系统通知（不代表已显示）";
            Persist();
        }
        RefreshStatus();
    }
    void PowerChanged(object? sender, PowerModeChangedEventArgs e) {
        if (e.Mode == PowerModes.Resume && IsHandleCreated) BeginInvoke((Action)(() => { Schedule.Rebuild(prefs, DateTime.Now); lastTick = DateTime.Now; Persist(); RefreshStatus(); }));
    }
    void TimeChanged(object? sender, EventArgs e) {
        if (IsHandleCreated) BeginInvoke((Action)(() => { Schedule.Rebuild(prefs, DateTime.Now); Persist(); RefreshStatus(); }));
    }
}
