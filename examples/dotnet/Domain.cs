using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Workshop;

public sealed record Exhibit(string Title, string Narrative, string[] Questions)
{
    public Exhibit Validate()
    {
        if (string.IsNullOrWhiteSpace(Title) || Title.IndexOfAny(['\r', '\n']) >= 0)
            throw new InvalidDataException("An exhibit needs exactly one single-line title.");
        var words = Regex.Matches(Narrative ?? "", @"\S+").Count;
        if (words is < 100 or > 140)
            throw new InvalidDataException($"Narrative has {words} words; expected 100-140.");
        if (Questions is not { Length: 3 } ||
            Questions.Any(q => string.IsNullOrWhiteSpace(q) || !q.TrimEnd().EndsWith('?')) ||
            Questions.Select(q => Regex.Replace(q.Trim(), @"\s+", " ")).Distinct(StringComparer.OrdinalIgnoreCase).Count() != 3)
            throw new InvalidDataException("An exhibit needs exactly three distinct visitor questions ending in '?'.");
        return this;
    }

    public string Markdown() =>
        $"# {Title}\n\n## Narrative\n{Narrative}\n\n## Visitor questions\n" +
        string.Join("\n", Questions.Select((q, i) => $"{i + 1}. {q}")) + $"\n\n{Contracts.GroundingDisclaimer}\n";
}

public sealed record Finding(string Title, string Evidence, string Criterion, string Remediation);

public sealed record AccessibilityReport(Finding[] Findings)
{
    public AccessibilityReport Validate()
    {
        if (Findings is not { Length: >= 3 and <= 5 })
            throw new InvalidDataException("Accessibility reports need 3-5 supported findings.");
        foreach (var finding in Findings)
        {
            if (finding is null || string.IsNullOrWhiteSpace(finding.Title) ||
                string.IsNullOrWhiteSpace(finding.Evidence) || string.IsNullOrWhiteSpace(finding.Remediation) ||
                finding.Criterion is null || !Contracts.Rules.ContainsKey(finding.Criterion))
                throw new InvalidDataException("Each finding needs observed evidence, a catalog criterion and remediation.");
        }
        return this;
    }

    public string Markdown() => "# Accessibility review\n\n" +
        string.Join("\n\n", Findings.Select((f, i) =>
            $"## Finding {i + 1}: {f.Title}\n- Evidence: {f.Evidence}\n" +
            $"- WCAG criterion: {f.Criterion} {Contracts.Rules[f.Criterion].Title}\n" +
            $"- Recommended remediation: {f.Remediation}")) +
        $"\n\n## Review limits\n{Contracts.ReviewLimits}\n";
}

public sealed record Source(string Title, string Url);
public sealed record Research(string Summary, Source[] Sources)
{
    public Research Validate()
    {
        if (string.IsNullOrWhiteSpace(Summary) || Sources is not { Length: >= 1 and <= 5 })
            throw new InvalidDataException("Research must include a summary and 1-5 cited Wikipedia articles.");
        foreach (var source in Sources)
        {
            if (source is null || string.IsNullOrWhiteSpace(source.Title) ||
                !Uri.TryCreate(source.Url, UriKind.Absolute, out var uri) ||
                uri.Scheme != "https" || !uri.Host.EndsWith(".wikipedia.org", StringComparison.OrdinalIgnoreCase) ||
                !uri.IsDefaultPort || uri.UserInfo.Length != 0 || !uri.AbsolutePath.StartsWith("/wiki/", StringComparison.Ordinal))
                throw new InvalidDataException("Research citations must be canonical HTTPS Wikipedia article URLs.");
        }
        return this;
    }
}

public sealed record Rule(string Title, string Recommendation, string[] Keywords);

public static class Contracts
{
    public const string GroundingDisclaimer =
        "Structural validation does not establish factual grounding. Unsupported claims require human review.";
    public const string ReviewLimits =
        "This is a focused review of browser-observable evidence, not a full WCAG conformance audit.";
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public static readonly IReadOnlyDictionary<string, string[]> FactSets = new Dictionary<string, string[]>
    {
        ["apollo11"] = [
            "Apollo 11 launched July 16, 1969.", "It landed on the Moon July 20, 1969.",
            "Neil Armstrong and Buzz Aldrin walked on the Moon.", "Michael Collins remained in lunar orbit.",
            "The mission returned to Earth July 24, 1969."
        ],
        ["reef"] = [
            "The Great Barrier Reef lies off Queensland, Australia.", "It extends for about 2,300 kilometres.",
            "It includes more than 2,900 individual reefs.", "It became a UNESCO World Heritage site in 1981.",
            "Rising sea temperatures have caused repeated coral bleaching."
        ],
        ["terracotta"] = [
            "The Terracotta Army was buried near the tomb of Qin Shi Huang.", "Farmers discovered the site in 1974.",
            "The pits contain thousands of life-sized clay soldiers.",
            "Figures were assembled from moulded parts and finished by hand.", "The site is near Xi'an in Shaanxi, China."
        ]
    };
    public static readonly IReadOnlyDictionary<string, Rule> Rules = new Dictionary<string, Rule>
    {
        ["1.1.1"] = new("Non-text Content", "Provide meaningful alternatives for informative images.", ["image", "alt"]),
        ["1.3.1"] = new("Info and Relationships", "Use semantic HTML for structure and relationships.", ["heading", "structure", "table"]),
        ["2.4.4"] = new("Link Purpose (In Context)", "Make link purpose understandable in context.", ["link"]),
        ["3.3.2"] = new("Labels or Instructions", "Associate visible labels and instructions with inputs.", ["label", "input", "form"]),
        ["4.1.2"] = new("Name, Role, Value", "Expose control names, roles, states and values.", ["button", "control", "role"])
    };

    public static string[] ValidateFacts(string[]? facts)
    {
        if (facts is not { Length: >= 1 and <= 20 } ||
            facts.Any(f => string.IsNullOrWhiteSpace(f) || f.Length > 500))
            throw new InvalidDataException("Supply 1-20 nonblank educator-approved facts, each at most 500 characters.");
        return facts.Select(f => f.Trim()).ToArray();
    }

    public static Uri Target(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https") ||
            uri.UserInfo.Length != 0 || value.Any(char.IsWhiteSpace))
            throw new ArgumentException("Provide an absolute HTTP(S) URL without credentials or whitespace.");
        return uri;
    }

    public static bool SameTarget(string candidate, Uri target)
    {
        try
        {
            var uri = Target(candidate);
            return uri.Scheme == target.Scheme && uri.Host == target.Host && uri.Port == target.Port &&
                uri.AbsolutePath == target.AbsolutePath && uri.Query == target.Query && uri.Fragment == target.Fragment;
        }
        catch (ArgumentException) { return false; }
    }

    public static string Html(string title, string body, int count, string label) => $$"""
        <!doctype html>
        <html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>{{WebUtility.HtmlEncode(title)}}</title>
        <style>body{font:1.1rem system-ui;max-width:60rem;margin:3rem auto;padding:1rem}
        :focus-visible{outline:3px solid #176b92;outline-offset:3px}input{font:inherit}</style></head>
        <body><main><h1>{{WebUtility.HtmlEncode(title)}}</h1>
        <label for="filter">Filter {{label}}</label> <input id="filter" type="search" aria-controls="items">
        <p id="count" role="status" aria-live="polite">{{count}} {{label}}</p>{{body}}</main>
        <script>const input=document.querySelector('#filter');input.addEventListener('input',()=>{
        let count=0;for(const item of document.querySelectorAll('[data-filter-item]')){
        item.hidden=!item.textContent.toLowerCase().includes(input.value.toLowerCase());if(!item.hidden)count++;}
        document.querySelector('#count').textContent=count+' {{label}}';});</script></body></html>
        """;

    public static string ExhibitHtml(Exhibit exhibit)
    {
        exhibit.Validate();
        var questions = string.Join("", exhibit.Questions.Select(q => $"<li data-filter-item>{WebUtility.HtmlEncode(q)}</li>"));
        return Html(exhibit.Title,
            $"<h2>Narrative</h2><p>{WebUtility.HtmlEncode(exhibit.Narrative)}</p>" +
            $"<section id=\"items\"><h2>Visitor questions</h2><ol>{questions}</ol></section>" +
            $"<p>{GroundingDisclaimer}</p>", 3, "questions");
    }

    public static string ReportHtml(AccessibilityReport report, Uri target)
    {
        report.Validate();
        var findings = string.Join("", report.Findings.Select(f =>
            $"<article data-filter-item><h3>{WebUtility.HtmlEncode(f.Title)}</h3><dl>" +
            $"<dt>Evidence</dt><dd>{WebUtility.HtmlEncode(f.Evidence)}</dd>" +
            $"<dt>WCAG criterion</dt><dd>{f.Criterion} {WebUtility.HtmlEncode(Rules[f.Criterion].Title)}</dd>" +
            $"<dt>Remediation</dt><dd>{WebUtility.HtmlEncode(f.Remediation)}</dd></dl></article>"));
        return Html("Accessibility review", $"<p>Target: {WebUtility.HtmlEncode(target.AbsoluteUri)}</p>" +
            $"<section id=\"items\"><h2>Findings</h2>{findings}</section><h2>Review limits</h2><p>{ReviewLimits}</p>",
            report.Findings.Length, "findings");
    }
}
