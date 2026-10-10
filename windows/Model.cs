using System.Text.Json;

namespace RestAndMove;
public sealed record Move(string id, string title, string steps, string source);
public sealed class Preferences {
    public bool Water { get; set; } = true;
    public bool Exercise { get; set; } = true;
    public int WaterMinutes { get; set; } = 30;
    public int ExerciseMinutes { get; set; } = 45;
    public int Start { get; set; } = 9;
    public int End { get; set; } = 18;
    public string[]? ExerciseIds { get; set; }
    public DateTime? NextWater { get; set; }
    public DateTime? NextExercise { get; set; }
    public DateTime? PausedUntil { get; set; }
    public int Cursor { get; set; }
    public string LastResult { get; set; } = "尚未触发提醒";
    public void Validate() {
        if (Start < 0 || End > 23 || Start >= End || WaterMinutes < 15 || WaterMinutes > 240 ||
            ExerciseMinutes < 15 || ExerciseMinutes > 240) throw new InvalidDataException("提醒设置无效");
    }
}
public sealed class SettingsStore(string path) {
    public string? Warning { get; private set; }
    public Preferences Load() {
        if (!File.Exists(path)) return new();
        try {
            var value = JsonSerializer.Deserialize<Preferences>(File.ReadAllText(path)) ?? throw new InvalidDataException();
            value.Validate(); return value;
        } catch (Exception e) when (e is JsonException or IOException or UnauthorizedAccessException) {
            Warning = "设置无法读取，已使用默认值；原文件保留。请检查磁盘或文件权限。";
            return new();
        }
    }
    public void Save(Preferences value) {
        value.Validate();
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(value, new JsonSerializerOptions { WriteIndented = true }));
        File.Move(temp, path, true);
    }
}
public static class Schedule {
    public static bool InWindow(Preferences p, DateTime now) => now.Hour >= p.Start && now.Hour < p.End;
    public static DateTime Next(DateTime now, int minutes, int start, int end) {
        var next = now.AddMinutes(minutes);
        if (next.Hour < start) return next.Date.AddHours(start);
        if (next.Hour >= end) return next.Date.AddDays(1).AddHours(start);
        return next;
    }
    public static void Rebuild(Preferences p, DateTime now, bool preserve = false) {
        p.NextWater = p.Water ? (preserve && p.NextWater > now ? p.NextWater : Next(now, p.WaterMinutes, p.Start, p.End)) : null;
        p.NextExercise = p.Exercise ? (preserve && p.NextExercise > now ? p.NextExercise : Next(now, p.ExerciseMinutes, p.Start, p.End)) : null;
    }
    public static (bool water, bool exercise) Tick(Preferences p, DateTime now) {
        if (p.PausedUntil is DateTime pause) {
            if (pause > now) return (false, false);
            p.PausedUntil = null; Rebuild(p, now); return (false, false);
        }
        if (!InWindow(p, now)) { Rebuild(p, now, true); return (false, false); }
        bool water = p.Water && p.NextWater <= now;
        bool exercise = p.Exercise && p.NextExercise <= now;
        if (water) p.NextWater = Next(now, p.WaterMinutes, p.Start, p.End);
        if (exercise) p.NextExercise = Next(now, p.ExerciseMinutes, p.Start, p.End);
        return (water, exercise);
    }
    public static Move? Pick(Preferences p, Move[] moves) {
        var eligible = moves.Where(m => p.ExerciseIds == null || p.ExerciseIds.Contains(m.id)).ToArray();
        if (eligible.Length == 0) return null;
        var move = eligible[(p.Cursor & int.MaxValue) % eligible.Length];
        p.Cursor = (p.Cursor & int.MaxValue) == int.MaxValue ? 0 : p.Cursor + 1;
        return move;
    }
}
