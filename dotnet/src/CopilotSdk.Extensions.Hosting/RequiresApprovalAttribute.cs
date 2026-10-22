namespace CopilotSdk.Extensions.Hosting;

/// <summary>
/// Marks a tool method that must be approved before it runs. The request is sent to
/// <see cref="CopilotOptions.OnApproval"/>, and denied when that isn't set.
/// </summary>
[AttributeUsage(AttributeTargets.Method, AllowMultiple = false, Inherited = true)]
public sealed class RequiresApprovalAttribute : Attribute
{
}
