using Microsoft.Extensions.AI;

/// <summary>Reads invoices from the queue and extracts structured data from each one.</summary>
public sealed class InvoiceProcessor(
    InvoiceQueue queue,
    IChatClient chat,
    IHostApplicationLifetime lifetime,
    ILogger<InvoiceProcessor> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var invoice in queue.ReadAllAsync(stoppingToken))
        {
            try
            {
                var response = await chat.GetResponseAsync<InvoiceExtraction>(
                    $"Extract the fields of this invoice:\n\n{invoice.Text}", cancellationToken: stoppingToken);
                var extraction = response.Result;
                logger.LogInformation("{File}: {Extraction}", invoice.FileName, extraction);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                logger.LogError(ex, "Could not process {File}.", invoice.FileName);
            }
        }

        // The sample queue is finite: exit once it is drained.
        lifetime.StopApplication();
    }
}
