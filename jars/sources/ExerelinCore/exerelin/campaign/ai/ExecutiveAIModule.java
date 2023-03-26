package exerelin.campaign.ai;

import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import exerelin.campaign.ai.action.StrategicAction;
import exerelin.campaign.ai.concern.StrategicConcern;
import exerelin.utilities.NexUtils;
import lombok.extern.log4j.Log4j;

import java.util.*;

@Log4j
public class ExecutiveAIModule extends StrategicAIModule {

	/*
        This should make the actual decisions
     */

    public Map<String, Float> recentActionsForAntiRepetition = new HashMap<>();

    public ExecutiveAIModule(StrategicAI ai) {
        super(ai, null);
    }

    public void reportRecentAction(StrategicAction action) {
        StrategicDefManager.StrategicActionDef def = action.getDef();
        NexUtils.modifyMapEntry(recentActionsForAntiRepetition, def.id, def.antiRepetition);
    }

    public float getAntiRepetitionValue(String actionDefId) {
        Float val = recentActionsForAntiRepetition.get(actionDefId);
        if (val == null) return 0;
        return val;
    }

    public List<StrategicAction> getRecentActions() {
        List<StrategicAction> list = new ArrayList<>();
        for (StrategicConcern concern : currentConcerns) {
            list.add(concern.getCurrentAction());
        }
        return list;
    }

    @Override
    public void advance(float days) {
        super.advance(days);
        for (String actionDefId : new ArrayList<>(recentActionsForAntiRepetition.keySet())) {
            float val = recentActionsForAntiRepetition.get(actionDefId);
            val -= days * SAIConstants.ANTI_REPETITION_DECAY_PER_DAY;
            if (val < 0) recentActionsForAntiRepetition.remove(actionDefId);
            else recentActionsForAntiRepetition.put(actionDefId, val);
        }

        List<StrategicConcern> concerns = new ArrayList<>(ai.getExistingConcerns());
        int numOngoingActions = 0;
        for (StrategicConcern concern : concerns) {
            if (concern.isEnded()) continue;
            StrategicAction act = concern.getCurrentAction();
            if (act != null && !act.isEnded()) {
                if (!act.isValid()) {
                    act.abort();
                }
            }
        }
    }

    public void actOnConcerns() {
        currentConcerns.clear();

        int actionsTakenThisMeeting = 0;
        List<StrategicConcern> concerns = new ArrayList<>(ai.getExistingConcerns());
        Collections.sort(concerns, new Comparator<StrategicConcern>() {
            @Override
            public int compare(StrategicConcern o1, StrategicConcern o2) {
                return Float.compare(o2.getPriorityFloat(), o1.getPriorityFloat());
            }
        });

        // count ongoing actions
        int numOngoingActions = 0;
        for (StrategicConcern concern : concerns) {
            if (concern.isEnded()) continue;
            StrategicAction act = concern.getCurrentAction();
            if (act != null && !act.isEnded()) {
                numOngoingActions++;
            }
        }

        for (StrategicConcern concern : concerns) {
            if (numOngoingActions > SAIConstants.MAX_SIMULTANEOUS_ACTIONS) break;

            if (concern.isEnded()) continue;
            if (concern.getCurrentAction() != null && !concern.getCurrentAction().isEnded()) continue;
            if (concern.getActionCooldown() > 0) continue;

            StrategicAction bestAction = concern.pickAction();
            if (bestAction == null) continue;
            boolean success = concern.initAction(bestAction);
            if (!success) continue;

            log.info("Adding action " + bestAction.getName());

            currentConcerns.add(concern);
            actionsTakenThisMeeting++;
            numOngoingActions++;
            if (actionsTakenThisMeeting >= SAIConstants.ACTIONS_PER_MEETING) break;
        }
    }

    @Override
    public void generateReport(TooltipMakerAPI tooltip, CustomPanelAPI holder, float width) {
        String str = StrategicAI.getString("intelPara_recentActions");
        tooltip.addPara(str, 10);
        super.generateReport(tooltip, holder, width);
    }
}
