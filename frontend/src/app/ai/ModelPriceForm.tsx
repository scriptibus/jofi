// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { type SyntheticEvent, useId, useState } from "react";
import {
  getListModelPricesQueryKey,
  type ModelPriceResponse,
  useSetModelPrice,
} from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, Form, NumberField, TextField } from "../../ui";
import { useFieldErrors } from "../auth/useFieldErrors";
import type { ErrorDescription } from "../problems";
import { FailureAlert } from "./FailureAlert";
import { microsToUsd, usdToMicros } from "./money";
import { describeSetupError, fieldErrorsOf } from "./setupProblems";

/** The server's limit: at most 10,000 US dollars per million tokens (backend `ModelPriceOverride`). */
const MAX_PRICE_USD = 10_000;
/** Down to the smallest step the API stores: one micro-dollar per million tokens. */
const PRICE_FORMAT: Intl.NumberFormatOptions = {
  style: "currency",
  currency: "USD",
  minimumFractionDigits: 2,
  maximumFractionDigits: 6,
};

export interface ModelPriceFormProps {
  providerId: string;
  /** The price to change (its model name stays fixed); without one the form adds a price. */
  price?: ModelPriceResponse;
  onSaved: (price: ModelPriceResponse) => void;
  onCancel?: () => void;
}

/** One price of a model of an OpenAI-compatible provider; prices are entered as US dollars per million tokens. */
export function ModelPriceForm({ providerId, price, onSaved, onCancel }: ModelPriceFormProps) {
  const queryClient = useQueryClient();
  const [model, setModel] = useState(price?.model ?? "");
  const [input, setInput] = useState(price ? microsToUsd(price.inputMicrosPerMillion) : Number.NaN);
  const [output, setOutput] = useState(price ? microsToUsd(price.outputMicrosPerMillion) : Number.NaN);
  const [failure, setFailure] = useState<ErrorDescription | null>(null);
  const fields = useFieldErrors();
  const save = useSetModelPrice({ mutation: { meta: { errorHandledLocally: true } } });
  const headingId = useId();

  const submit = (event: SyntheticEvent<HTMLFormElement>) => {
    event.preventDefault();
    setFailure(null);
    fields.set({});
    save.mutate(
      {
        id: providerId,
        data: {
          model,
          inputMicrosPerMillion: usdToMicros(input),
          outputMicrosPerMillion: usdToMicros(output),
        },
      },
      {
        onSuccess: async (saved) => {
          await queryClient.invalidateQueries({ queryKey: getListModelPricesQueryKey(providerId) });
          onSaved(saved);
          if (!price) {
            setModel("");
            setInput(Number.NaN);
            setOutput(Number.NaN);
          }
        },
        onError: (error) => {
          const errors = fieldErrorsOf(error);
          if (Object.keys(errors).length > 0) fields.set(errors);
          else setFailure(describeSetupError(error));
        },
      },
    );
  };

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded border border-line p-4">
      <h4 id={headingId} className="text-h3">
        {price ? m.ai_price_edit_heading({ model: price.model }) : m.ai_price_add_heading()}
      </h4>
      <FailureAlert failure={failure} />
      <Form onSubmit={submit} validationErrors={fields.errors} className="flex max-w-md flex-col gap-4">
        <TextField
          name="model"
          label={m.ai_price_model_label()}
          description={m.ai_price_model_description()}
          value={model}
          onChange={fields.clearing("model", setModel)}
          isReadOnly={price !== undefined}
          isRequired
          mono
          autoComplete="off"
        />
        <NumberField
          name="inputMicrosPerMillion"
          label={m.ai_price_input_label()}
          description={m.ai_price_zero_hint()}
          value={input}
          onChange={fields.clearing("inputMicrosPerMillion", setInput)}
          minValue={0}
          maxValue={MAX_PRICE_USD}
          formatOptions={PRICE_FORMAT}
          isRequired
        />
        <NumberField
          name="outputMicrosPerMillion"
          label={m.ai_price_output_label()}
          value={output}
          onChange={fields.clearing("outputMicrosPerMillion", setOutput)}
          minValue={0}
          maxValue={MAX_PRICE_USD}
          formatOptions={PRICE_FORMAT}
          isRequired
        />
        <div className="flex flex-wrap gap-3">
          <Button type="submit" isDisabled={save.isPending}>
            {m.ai_price_save()}
          </Button>
          {onCancel ? (
            <Button variant="secondary" onPress={onCancel}>
              {m.ai_cancel()}
            </Button>
          ) : null}
        </div>
      </Form>
    </section>
  );
}
