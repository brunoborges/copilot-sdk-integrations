namespace Workshop;

public sealed class RunState(string[] facts, string outputDirectory, Uri? target = null)
{
    public string[] Facts { get; } = Contracts.ValidateFacts(facts);
    public string OutputDirectory { get; } = Path.GetFullPath(outputDirectory);
    public Uri? Target { get; } = target;
    public Research? Research { get; set; }
    public HashSet<string> Called { get; } = [];
    public HashSet<string> LookedUp { get; } = [];

    public object ApprovedFactLookup()
    {
        lock (Called) Called.Add("approved_fact_lookup");
        return new { facts = Facts.ToArray() };
    }

    public object ApprovedWikipediaFactLookup()
    {
        var research = Research ?? throw new InvalidOperationException("No accepted research is available.");
        lock (Called) Called.Add("approved_wikipedia_fact_lookup");
        return research;
    }

    public string ReadLatestAccessibilitySnapshot()
    {
        var latest = new DirectoryInfo(OutputDirectory).EnumerateFiles("page-*.yml")
            .Where(file => (file.Attributes & FileAttributes.ReparsePoint) == 0 &&
                file.Length is > 0 and <= 1_000_000)
            .OrderByDescending(file => file.LastWriteTimeUtc).FirstOrDefault()
            ?? throw new InvalidOperationException("No current-run snapshot. Navigate to the requested URL first.");
        using var file = latest.OpenRead();
        using var buffer = new MemoryStream();
        var bytes = new byte[8192];
        int read;
        while ((read = file.Read(bytes)) > 0)
        {
            if (buffer.Length + read > 1_000_000) throw new InvalidDataException("Snapshot exceeds 1 MB.");
            buffer.Write(bytes, 0, read);
        }
        var result = new System.Text.UTF8Encoding(false, true).GetString(buffer.ToArray());
        lock (Called) Called.Add("read_latest_accessibility_snapshot");
        return result;
    }

    public object AccessibilityRuleLookup(string query)
    {
        var match = Contracts.Rules.FirstOrDefault(pair =>
            query.Contains(pair.Key, StringComparison.OrdinalIgnoreCase) ||
            query.Contains(pair.Value.Title, StringComparison.OrdinalIgnoreCase) ||
            pair.Value.Keywords.Any(keyword => query.Contains(keyword, StringComparison.OrdinalIgnoreCase)));
        if (match.Key is null)
            throw new ArgumentException("No local WCAG catalog match; consult the full reference.");
        lock (LookedUp) LookedUp.Add(match.Key);
        return new { criterion = match.Key, match.Value.Title, match.Value.Recommendation };
    }

    public void VerifyMuseum()
    {
        if (!Called.Contains("approved_fact_lookup") ||
            (Research is not null && !Called.Contains("approved_wikipedia_fact_lookup")))
            throw new InvalidDataException("The curator did not invoke the required fact lookup tools.");
    }

    public void VerifyReport(AccessibilityReport report)
    {
        if (!Called.Contains("read_latest_accessibility_snapshot") ||
            report.Findings.Any(f => !LookedUp.Contains(f.Criterion)))
            throw new InvalidDataException("The reviewer must read this run's snapshot and look up every finding's criterion.");
    }
}
