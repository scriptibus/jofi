// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type {
  CapabilityNeedsResponse,
  CapabilityNeedsResponseFeaturesItem,
  ModelResponse,
  ProviderResponseKind,
  TaskAssignmentResponseTask,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { formatCount } from "../backup/files";

export type AiTask = TaskAssignmentResponseTask;
type Feature = CapabilityNeedsResponseFeaturesItem;

export interface TaskGroup {
  id: "cheap" | "strong" | "search" | "voice";
  title: () => string;
  description: () => string;
  tasks: readonly AiTask[];
}

/** The task groups of spec §3.2: cheap and fast, strong, search (embeddings) and voice. */
export const TASK_GROUPS: readonly TaskGroup[] = [
  {
    id: "cheap",
    title: m.ai_group_cheap,
    description: m.ai_group_cheap_description,
    tasks: ["SCANNER_PRE_SCORING", "CLASSIFICATION", "LANGUAGE_TONE_DETECTION"],
  },
  {
    id: "strong",
    title: m.ai_group_strong,
    description: m.ai_group_strong_description,
    tasks: ["EXTRACTION", "KNOWLEDGE_INTERVIEW", "DOCUMENT_GENERATION", "INTERVIEW_TRAINING", "CHAT"],
  },
  {
    id: "search",
    title: m.ai_group_search,
    description: m.ai_group_search_description,
    tasks: ["EMBEDDING"],
  },
  {
    id: "voice",
    title: m.ai_group_voice,
    description: m.ai_group_voice_description,
    tasks: ["SPEECH_TO_TEXT", "TEXT_TO_SPEECH"],
  },
];

export const TASK_LABELS: Record<AiTask, () => string> = {
  SCANNER_PRE_SCORING: m.ai_task_scanner_pre_scoring,
  CLASSIFICATION: m.ai_task_classification,
  LANGUAGE_TONE_DETECTION: m.ai_task_language_tone_detection,
  EXTRACTION: m.ai_task_extraction,
  KNOWLEDGE_INTERVIEW: m.ai_task_knowledge_interview,
  DOCUMENT_GENERATION: m.ai_task_document_generation,
  INTERVIEW_TRAINING: m.ai_task_interview_training,
  CHAT: m.ai_task_chat,
  EMBEDDING: m.ai_task_embedding,
  SPEECH_TO_TEXT: m.ai_task_speech_to_text,
  TEXT_TO_SPEECH: m.ai_task_text_to_speech,
};

export const PROVIDER_KIND_LABELS: Record<ProviderResponseKind, () => string> = {
  ANTHROPIC: m.ai_kind_anthropic,
  OPENAI: m.ai_kind_openai,
  GEMINI: m.ai_kind_gemini,
  MISTRAL: m.ai_kind_mistral,
  OPENAI_COMPATIBLE: m.ai_kind_openai_compatible,
};

const FEATURE_LABELS: Record<Feature, () => string> = {
  TOOL_USE: m.ai_capability_tool_use,
  STREAMING: m.ai_capability_streaming,
  SPEECH_TO_TEXT: m.ai_capability_speech_to_text,
  TEXT_TO_SPEECH: m.ai_capability_text_to_speech,
  EMBEDDING: m.ai_capability_embedding,
};

/** What a model lacks for a task, one phrase per capability ("tool use", "a context of 32,768 tokens"). */
export function describeCapabilities(needs: CapabilityNeedsResponse): string[] {
  const phrases = needs.features.map((feature) => FEATURE_LABELS[feature]());
  if (needs.minContextWindowTokens != null)
    phrases.push(m.ai_capability_context({ tokens: formatCount(needs.minContextWindowTokens) }));
  return phrases;
}

/** A model offered for a task: which provider and what it can do (from the last connection test). */
export interface Candidate {
  providerId: string;
  model: ModelResponse;
}

const SMALL_MODEL = /mini|nano|haiku|flash|small|lite|tiny/i;

function meets(model: ModelResponse, needs: CapabilityNeedsResponse): boolean {
  const context = needs.minContextWindowTokens ?? 0;
  return (
    needs.features.every((feature) => model.features.includes(feature)) &&
    (context === 0 || (model.contextWindowTokens ?? 0) >= context)
  );
}

/**
 * A sensible default for a task: a model that has what the task needs, small and cheap for the cheap
 * group, large for the rest (by the usual naming: mini, haiku, flash, small...). Undefined when no
 * listed model fits; the user then chooses and sees the warnings.
 */
export function suggestModel(
  task: AiTask,
  needs: CapabilityNeedsResponse,
  candidates: readonly Candidate[],
): Candidate | undefined {
  const fitting = candidates.filter((candidate) => meets(candidate.model, needs));
  const wantsSmall = TASK_GROUPS.find((group) => group.tasks.includes(task))?.id === "cheap";
  const preferred = fitting.find((candidate) => SMALL_MODEL.test(candidate.model.model) === wantsSmall);
  return preferred ?? fitting[0];
}

/** Select option id of a provider's model; JSON, since model names contain `/`, `:` and more. */
export function optionId(providerId: string, model: string): string {
  return JSON.stringify([providerId, model]);
}

export function parseOptionId(id: string): { providerId: string; model: string } | undefined {
  try {
    const value: unknown = JSON.parse(id);
    if (Array.isArray(value) && typeof value[0] === "string" && typeof value[1] === "string")
      return { providerId: value[0], model: value[1] };
  } catch {
    // Not one of ours.
  }
  return undefined;
}
