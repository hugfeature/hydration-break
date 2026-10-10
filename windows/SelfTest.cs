using System.Text.Json;
namespace RestAndMove;
static class SelfTest {
    static void Check(bool passed, string name) { if (!passed) throw new Exception(name); }
    public static int Run(string[] args) {
        var output = args.SkipWhile(x => x != "--self-test").Skip(1).FirstOrDefault() ?? "self-test.txt";
        try {
            var now = new DateTime(2026, 10, 10, 10, 0, 0);
            var p = new Preferences();
            Schedule.Rebuild(p, now);
            Check(p.NextWater == now.AddMinutes(30) && p.NextExercise == now.AddMinutes(45), "independent intervals");
            Check(!Schedule.Tick(p, now.AddMinutes(29)).water, "no early notification");
            Check(Schedule.Tick(p, now.AddMinutes(30)) == (true, false), "water only");
            Check(Schedule.Tick(p, now.AddMinutes(45)) == (false, true), "exercise only");
            p.PausedUntil = now.AddHours(2);
            Check(Schedule.Tick(p, now.AddHours(1)) == (false,false), "paused");
            Check(Schedule.Tick(p, now.AddHours(3)) == (false,false), "resume without burst");
            Schedule.Rebuild(p, now.AddHours(4));
            Check(p.NextWater == now.AddHours(4).AddMinutes(30), "sleep resume fresh interval");
            Check(Schedule.Next(now.Date.AddHours(17).AddMinutes(50), 30, 9, 18) == now.Date.AddDays(1).AddHours(9), "end exclusive");
            p.Water = false; Schedule.Rebuild(p, now); Check(p.NextWater == null, "disabled");
            var moves = new[] { new Move("a","a","a","a"),new Move("b","b","b","b") };
            p.ExerciseIds = ["b"]; Check(Schedule.Pick(p,moves)?.id == "b", "selection");
            p.ExerciseIds = []; Check(Schedule.Pick(p,moves) == null, "empty selection");
            var dir = Path.Combine(Path.GetTempPath(), "RestAndMoveTest-" + Guid.NewGuid());
            try {
                var store = new SettingsStore(Path.Combine(dir,"settings.json"));
                p.WaterMinutes = 90; store.Save(p);
                Check(store.Load().WaterMinutes == 90, "settings retained");
                File.WriteAllText(Path.Combine(dir,"settings.json"), "{bad");
                Check(store.Load().WaterMinutes == 30 && store.Warning != null, "corrupt settings recovered with warning");
            } finally { if (Directory.Exists(dir)) Directory.Delete(dir,true); }
            File.WriteAllText(output, "PASS: independent intervals, due delivery, pause, resume, window, disable, selection, persistence, corrupt recovery");
            return 0;
        } catch (Exception e) { File.WriteAllText(output, "FAIL: " + e); return 1; }
    }
}
