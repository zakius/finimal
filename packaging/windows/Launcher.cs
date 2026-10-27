// finimal.exe of the small Windows build, which carries neither a Java runtime nor libmpv. It starts the app on the
// newest installed Java 21 or later, with libmpv-2.dll from %APPDATA%\finimal or the PATH. Whatever is missing it
// downloads once: Eclipse Temurin's JRE to %LOCALAPPDATA%\finimal\runtime, and the libmpv the full build carries to
// %APPDATA%\finimal, each checked against the SHA-256 pinned at build time (Pins.cs, from tools/windows-small.sh).
// The Java options come from app\finimal.cfg, which jpackage writes for the full build's own launcher.
// C# 5: the compiler that comes with .NET Framework 4, which every Windows 10 and 11 has, builds it.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Launcher
{
    static readonly string Local = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "finimal");
    static readonly string Roaming = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "finimal");

    [STAThread]
    static int Main(string[] args)
    {
        // CI runs --check-player unattended: no windows, errors to stderr.
        bool quiet = Array.IndexOf(args, "--check-player") >= 0;
        try
        {
            ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
            string app = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "app");
            string java = FindJava();
            bool mpv = HasMpv();
            if (java == null || !mpv) Download(java == null, !mpv, quiet);
            if (java == null) java = FindJava();
            if (java == null) throw new Exception("The downloaded Java runtime doesn't start.");
            if (quiet) Console.WriteLine("Java: " + java);
            return Run(java, app, args);
        }
        catch (Exception e)
        {
            string text = "finimal can't start: " + e.Message + "\n\nIt needs Java 21 or newer (Eclipse Temurin, for one) and libmpv-2.dll in " +
                Roaming + ". The full finimal download includes both.";
            if (quiet) Console.Error.WriteLine(text);
            else MessageBox.Show(text, "finimal", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    // The newest Java 21 or later with the desktop module: the one downloaded before, JAVA_HOME, the PATH, then what
    // installers register (Oracle and most others under JavaSoft, Temurin, Microsoft's build, Azul Zulu).
    static string FindJava()
    {
        var homes = new List<string>();
        homes.Add(Path.Combine(Local, "runtime", Pins.JreDir));
        homes.Add(Environment.GetEnvironmentVariable("JAVA_HOME"));
        foreach (string dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            if (dir.Trim().Length > 0) homes.Add(Path.Combine(dir.Trim(), ".."));
        foreach (RegistryHive hive in new[] { RegistryHive.LocalMachine, RegistryHive.CurrentUser })
        {
            AddHomes(homes, hive, @"SOFTWARE\JavaSoft\JDK", "", "JavaHome");
            AddHomes(homes, hive, @"SOFTWARE\JavaSoft\JRE", "", "JavaHome");
            AddHomes(homes, hive, @"SOFTWARE\Eclipse Adoptium\JDK", @"\hotspot\MSI", "Path");
            AddHomes(homes, hive, @"SOFTWARE\Eclipse Adoptium\JRE", @"\hotspot\MSI", "Path");
            AddHomes(homes, hive, @"SOFTWARE\Microsoft\JDK", @"\hotspot\MSI", "Path");
            AddHomes(homes, hive, @"SOFTWARE\Azul Systems\Zulu", "", "InstallationPath");
        }
        string best = null;
        int bestVersion = 20;
        foreach (string home in homes)
        {
            if (string.IsNullOrEmpty(home)) continue;
            int version = JavaVersion(home);
            if (version > bestVersion) { best = Path.GetFullPath(Path.Combine(home, "bin", "javaw.exe")); bestVersion = version; }
        }
        return best;
    }

    static void AddHomes(List<string> homes, RegistryHive hive, string path, string suffix, string value)
    {
        try
        {
            using (RegistryKey root = RegistryKey.OpenBaseKey(hive, RegistryView.Registry64))
            using (RegistryKey key = root.OpenSubKey(path))
            {
                if (key == null) return;
                foreach (string version in key.GetSubKeyNames())
                    using (RegistryKey sub = root.OpenSubKey(path + "\\" + version + suffix))
                        if (sub != null) homes.Add(sub.GetValue(value) as string);
            }
        }
        catch (Exception) { }
    }

    // The feature version from the runtime's release file, or 0 when it isn't a usable desktop Java.
    static int JavaVersion(string home)
    {
        try
        {
            if (!File.Exists(Path.Combine(home, "bin", "javaw.exe")) || !File.Exists(Path.Combine(home, "bin", "awt.dll"))) return 0;
            foreach (string line in File.ReadAllLines(Path.Combine(home, "release")))
                if (line.StartsWith("JAVA_VERSION=\""))
                {
                    string v = line.Substring(14);
                    int end = 0;
                    while (end < v.Length && char.IsDigit(v[end])) end++;
                    return end > 0 ? int.Parse(v.Substring(0, end)) : 0;
                }
        }
        catch (Exception) { }
        return 0;
    }

    static bool HasMpv()
    {
        if (File.Exists(Path.Combine(Roaming, "libmpv-2.dll"))) return true;
        foreach (string dir in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(';'))
            try { if (dir.Trim().Length > 0 && File.Exists(Path.Combine(dir.Trim(), "libmpv-2.dll"))) return true; }
            catch (Exception) { }
        return false;
    }

    // Both downloads, behind a small progress window unless quiet.
    static void Download(bool java, bool mpv, bool quiet)
    {
        Exception failure = null;
        Form form = null;
        Label label = null;
        ProgressBar bar = null;
        Action<string, int> show = delegate { };
        if (!quiet)
        {
            form = new Form { Text = "finimal", Width = 420, Height = 130, FormBorderStyle = FormBorderStyle.FixedDialog,
                MaximizeBox = false, MinimizeBox = false, StartPosition = FormStartPosition.CenterScreen };
            label = new Label { Left = 16, Top = 14, Width = 370 };
            bar = new ProgressBar { Left = 16, Top = 42, Width = 370, Maximum = 100 };
            form.Controls.Add(label);
            form.Controls.Add(bar);
            show = delegate(string text, int percent)
            {
                form.BeginInvoke((Action)delegate { label.Text = text; bar.Value = Math.Max(0, Math.Min(100, percent)); });
            };
        }
        var work = new Thread(delegate()
        {
            try
            {
                if (java)
                {
                    string dir = Path.Combine(Local, "runtime");
                    Fetch(Pins.JreUrl, Pins.JreSha256, dir, "Downloading Java (first start only)", show);
                }
                if (mpv) Fetch(Pins.MpvUrl, Pins.MpvSha256, Roaming, "Downloading mpv (first start only)", show);
            }
            catch (Exception e) { failure = e; }
            if (form != null) form.BeginInvoke((Action)form.Close);
        });
        if (form != null) form.Shown += delegate { work.Start(); };
        else work.Start();
        if (form != null) Application.Run(form);
        work.Join();
        if (failure != null) throw failure;
    }

    // Downloads a zip, checks its SHA-256 and unpacks it into dir; a partial download leaves nothing behind.
    static void Fetch(string url, string sha256, string dir, string what, Action<string, int> show)
    {
        Directory.CreateDirectory(dir);
        string zip = Path.Combine(dir, "download.part");
        var request = (HttpWebRequest)WebRequest.Create(url);
        request.UserAgent = "finimal";
        using (var response = request.GetResponse())
        using (var input = response.GetResponseStream())
        using (var output = File.Create(zip))
        using (var hash = SHA256.Create())
        {
            long total = response.ContentLength, done = 0;
            var buffer = new byte[1 << 16];
            int n;
            while ((n = input.Read(buffer, 0, buffer.Length)) > 0)
            {
                output.Write(buffer, 0, n);
                hash.TransformBlock(buffer, 0, n, null, 0);
                done += n;
                show(what + ": " + (done >> 20) + " of " + (total >> 20) + " MB", total > 0 ? (int)(done * 100 / total) : 0);
            }
            hash.TransformFinalBlock(buffer, 0, 0);
            string got = BitConverter.ToString(hash.Hash).Replace("-", "").ToLowerInvariant();
            if (got != sha256) { output.Close(); File.Delete(zip); throw new Exception("the download from " + url + " is corrupt."); }
        }
        show(what + ": unpacking", 100);
        string unpacked = Path.Combine(dir, "download.tmp");
        if (Directory.Exists(unpacked)) Directory.Delete(unpacked, true);
        ZipFile.ExtractToDirectory(zip, unpacked);
        File.Delete(zip);
        foreach (string entry in Directory.GetFileSystemEntries(unpacked))
        {
            string target = Path.Combine(dir, Path.GetFileName(entry));
            if (Directory.Exists(target)) Directory.Delete(target, true);
            else if (File.Exists(target)) File.Delete(target);
            if (Directory.Exists(entry)) Directory.Move(entry, target);
            else File.Move(entry, target);
        }
        Directory.Delete(unpacked, true);
    }

    // javaw with app\finimal.cfg's class path, options and main class, as jpackage's launcher would; waits for it, so
    // the exit code and any redirected output come through.
    static int Run(string java, string app, string[] args)
    {
        var classpath = new List<string>();
        var command = new StringBuilder();
        string main = null;
        foreach (string raw in File.ReadAllLines(Path.Combine(app, "finimal.cfg")))
        {
            string line = raw.Replace("$APPDIR", app);
            if (line.StartsWith("app.classpath=")) classpath.Add(line.Substring(14));
            else if (line.StartsWith("app.mainclass=")) main = line.Substring(14);
            else if (line.StartsWith("java-options=")) command.Append(Quote(line.Substring(13))).Append(' ');
        }
        command.Append("-cp ").Append(Quote(string.Join(";", classpath))).Append(' ').Append(main);
        foreach (string arg in args) command.Append(' ').Append(Quote(arg));
        var start = new ProcessStartInfo(java, command.ToString()) { UseShellExecute = false };
        using (Process process = Process.Start(start))
        {
            process.WaitForExit();
            return process.ExitCode;
        }
    }

    // One argument as Windows programs parse their command line.
    static string Quote(string arg)
    {
        if (arg.Length > 0 && arg.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return arg;
        var s = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in arg)
        {
            if (c == '\\') { slashes++; continue; }
            s.Append('\\', c == '"' ? slashes * 2 + 1 : slashes).Append(c);
            slashes = 0;
        }
        return s.Append('\\', slashes * 2).Append('"').ToString();
    }
}
