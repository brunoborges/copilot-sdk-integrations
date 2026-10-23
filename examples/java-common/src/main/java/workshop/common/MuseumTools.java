package workshop.common;

import com.github.copilot.tool.CopilotTool;

public class MuseumTools {
    private Exhibit exhibit;
    private boolean consulted;

    public void prepare(Exhibit exhibit) {
        this.exhibit = exhibit;
        consulted = false;
    }

    @CopilotTool(name = "approved_fact_lookup", value = "Read the selected exhibit's educator-approved facts; no outside research")
    public String approvedFacts() {
        if (exhibit == null) throw new IllegalStateException("No exhibit selected");
        consulted = true;
        return Json.encode(exhibit);
    }

    public boolean consulted() { return consulted; }
}
