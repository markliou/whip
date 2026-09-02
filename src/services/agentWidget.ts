import { NativeModules, Platform } from 'react-native';

import type { LiveHostSessionsState } from '../liveHostSessions';

interface AgentStatusWidgetNativeModule {
  updateSnapshot(snapshotJson: string): Promise<void>;
}

function nativeModule(): AgentStatusWidgetNativeModule | null {
  if (Platform.OS !== 'android') return null;
  const module = NativeModules.AgentStatusWidget as
    | AgentStatusWidgetNativeModule
    | undefined;
  if (!module) {
    throw new Error('AgentStatusWidget native module is not installed in this build');
  }
  return module;
}

export async function updateAgentWidgetSnapshot(
  state: LiveHostSessionsState,
): Promise<void> {
  const module = nativeModule();
  if (!module) return;

  const snapshot = {
    schemaVersion: 1,
    updatedAtMs: Date.now(),
    hosts: state.sessions.map(session => {
      const workspaceLabels = new Map(
        session.snapshot.workspaces.map(workspace => [
          workspace.workspace_id,
          workspace.label,
        ]),
      );
      const tabLabels = new Map(
        session.snapshot.tabs.map(tab => [tab.tab_id, tab.label]),
      );
      return {
        id: session.hostId,
        label: session.host.name,
        connectionStatus: session.status,
        freshness: session.sync.freshness,
        serverRunning: session.snapshot.server.running,
        agents: session.snapshot.agents.map(agent => ({
          paneId: agent.pane_id,
          status: agent.agent_status,
          label: agent.agent ?? `Pane ${agent.pane_id}`,
          workspaceLabel:
            workspaceLabels.get(agent.workspace_id) ?? agent.workspace_id,
          tabLabel: tabLabels.get(agent.tab_id) ?? agent.tab_id,
        })),
      };
    }),
  };

  await module.updateSnapshot(JSON.stringify(snapshot));
}
