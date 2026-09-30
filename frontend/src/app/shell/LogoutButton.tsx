// SPDX-FileCopyrightText: 2026 Jofi contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "@tanstack/react-router";
import { useLogOut } from "../../api/generated/jofi";
import { m } from "../../paraglide/messages.js";
import { Button, LogOutIcon } from "../../ui";
import { markLoggedOut } from "../auth/session";

/**
 * Ends this session. Afterwards the cached data of the session is dropped and the session status
 * (with a fresh CSRF cookie) fetched again, then the login screen says so.
 */
export function LogoutButton({ className = "" }: { className?: string }) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const logout = useLogOut();

  const onPress = () =>
    logout.mutate(undefined, {
      onSuccess: async () => {
        markLoggedOut(queryClient);
        await router.navigate({ to: "/login", search: { reason: "logged-out" } });
      },
    });

  return (
    <Button variant="secondary" onPress={onPress} isPending={logout.isPending} className={className}>
      <LogOutIcon className="size-4" aria-hidden="true" />
      {logout.isPending ? m.logout_pending() : m.logout()}
    </Button>
  );
}
