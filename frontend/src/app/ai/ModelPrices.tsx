// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import {
  getListModelPricesQueryKey,
  type ModelPriceResponse,
  type ProviderResponse,
  useClearModelPrice,
  useListModelPrices,
  useListProviders,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Alert, Button, DeleteIcon, EditIcon } from "../../ui";
import type { ErrorDescription } from "../problems";
import { FailureAlert } from "./FailureAlert";
import { ModelPriceForm } from "./ModelPriceForm";
import { formatUsdPrice } from "./money";
import { describeSetupError } from "./setupProblems";

/**
 * Settings > AI: the prices the user gives the models of their OpenAI-compatible providers (spec §3.2,
 * ADR-0055), so those calls get a cost and count toward the monthly cap. Cloud providers have list prices
 * and are not shown. A price applies to later calls only; recorded entries keep their cost.
 */
export function ModelPrices() {
  const providers = useListProviders();
  const compatible = (providers.data ?? []).filter((provider) => provider.kind === "OPENAI_COMPATIBLE");
  return (
    <div className="flex flex-col gap-4">
      <p className="max-w-prose text-muted">{m.ai_prices_intro()}</p>
      <p className="max-w-prose text-muted">{m.ai_prices_note()}</p>
      {providers.isSuccess && compatible.length === 0 ? <p>{m.ai_prices_no_provider()}</p> : null}
      {compatible.map((provider) => (
        <ProviderPrices key={provider.id} provider={provider} />
      ))}
    </div>
  );
}

function ProviderPrices({ provider }: { provider: ProviderResponse }) {
  const prices = useListModelPrices(provider.id);
  const [editing, setEditing] = useState<string | null>(null);
  const [status, setStatus] = useState<string | null>(null);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const list = prices.data ?? [];
  const headingId = `prices-${provider.id}`;

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 rounded border border-line p-4">
      <h3 id={headingId} className="text-h3">
        {m.ai_prices_list_label({ provider: provider.displayName })}
      </h3>
      {status ? <Alert tone="success">{status}</Alert> : null}
      <FailureAlert failure={failure} />
      {prices.isSuccess && list.length === 0 ? <p className="text-muted">{m.ai_prices_empty()}</p> : null}
      {list.length > 0 ? (
        <ul className="flex flex-col gap-3">
          {list.map((price) => (
            <li key={price.model}>
              {editing === price.model ? (
                <ModelPriceForm
                  providerId={provider.id}
                  price={price}
                  onSaved={(saved) => {
                    setEditing(null);
                    setFailure(null);
                    setStatus(m.ai_price_saved({ model: saved.model }));
                  }}
                  onCancel={() => setEditing(null)}
                />
              ) : (
                <PriceRow
                  providerId={provider.id}
                  price={price}
                  onEdit={() => {
                    setStatus(null);
                    setEditing(price.model);
                  }}
                  onRemoved={() => {
                    setFailure(null);
                    setStatus(m.ai_price_removed({ model: price.model }));
                  }}
                  onFailed={(error) => {
                    setStatus(null);
                    setFailure(describeSetupError(error));
                  }}
                />
              )}
            </li>
          ))}
        </ul>
      ) : null}
      <ModelPriceForm
        providerId={provider.id}
        onSaved={(saved) => {
          setFailure(null);
          setStatus(m.ai_price_saved({ model: saved.model }));
        }}
      />
    </section>
  );
}

interface PriceRowProps {
  providerId: string;
  price: ModelPriceResponse;
  onEdit: () => void;
  onRemoved: () => void;
  onFailed: (error: unknown) => void;
}

function PriceRow({ providerId, price, onEdit, onRemoved, onFailed }: PriceRowProps) {
  const queryClient = useQueryClient();
  const clear = useClearModelPrice({ mutation: { meta: { errorHandledLocally: true } } });
  const remove = () =>
    clear.mutate(
      { id: providerId, params: { model: price.model } },
      {
        onSuccess: async () => {
          await queryClient.invalidateQueries({ queryKey: getListModelPricesQueryKey(providerId) });
          onRemoved();
        },
        onError: onFailed,
      },
    );
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="flex min-w-0 flex-col">
        <span className="break-all font-data">{price.model}</span>
        <span className="text-muted">
          {m.ai_price_row({
            input: formatUsdPrice(price.inputMicrosPerMillion),
            output: formatUsdPrice(price.outputMicrosPerMillion),
          })}
        </span>
      </div>
      <div className="flex flex-wrap gap-3">
        <Button
          variant="secondary"
          aria-label={m.ai_price_edit_label({ model: price.model })}
          onPress={onEdit}
        >
          <EditIcon className="size-4" aria-hidden="true" />
          {m.ai_price_edit()}
        </Button>
        <Button
          variant="secondary"
          aria-label={m.ai_price_remove_label({ model: price.model })}
          onPress={remove}
          isDisabled={clear.isPending}
        >
          <DeleteIcon className="size-4" aria-hidden="true" />
          {m.ai_price_remove()}
        </Button>
      </div>
    </div>
  );
}
