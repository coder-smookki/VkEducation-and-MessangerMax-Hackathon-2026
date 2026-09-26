import {
  QueryClient,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useRef } from "react";
import { ApiError, command, get, send } from "./client";
import type { Case, Command } from "./client";
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 0, refetchOnWindowFocus: false },
    mutations: { retry: false, networkMode: "always" },
  },
});
export const caseKey = (id: string) => ["case", id] as const;
export function useCase(id: string) {
  return useQuery({
    queryKey: caseKey(id),
    queryFn: ({ signal }) => get<Case>(`/cases/${id}`, signal),
  });
}
export function useCommand<T>(onSuccess: (result: T) => void) {
  const retained = useRef<Command | null>(null);
  const inFlight = useRef(false);
  const mutation = useMutation({
    mutationFn: (operation: Command) => send<T>(operation),
    onSuccess: (result) => {
      retained.current = null;
      onSuccess(result);
    },
    onSettled: () => {
      inFlight.current = false;
    },
  });
  function run(
    path: string,
    body: unknown,
    method: Command["method"] = "POST",
  ) {
    if (inFlight.current) return;
    if (
      retained.current &&
      mutation.error instanceof ApiError &&
      (mutation.error.unknownOutcome || mutation.error.status === 401)
    )
      return;
    retained.current = command(path, body, method);
    inFlight.current = true;
    mutation.mutate(retained.current);
  }
  function retry() {
    if (!retained.current || inFlight.current) return;
    inFlight.current = true;
    mutation.mutate(retained.current);
  }
  return {
    ...mutation,
    run,
    retry,
    locked:
      mutation.isPending ||
      (mutation.error instanceof ApiError &&
        (mutation.error.unknownOutcome || mutation.error.status === 401)),
  };
}
export function useCaseSaved() {
  const cache = useQueryClient();
  return (data: Case) => {
    // An idempotent replay can return an old projection; do not roll the cache back.
    cache.setQueryData<Case>(caseKey(data.caseId), (previous) =>
      previous && previous.caseVersion > data.caseVersion ? previous : data,
    );
    void cache.invalidateQueries({ queryKey: ["cases"] });
    void cache.invalidateQueries({ queryKey: ["history", data.caseId] });
    void cache.invalidateQueries({ queryKey: caseKey(data.caseId) });
    void cache.invalidateQueries({ queryKey: ["report"] });
    void cache.invalidateQueries({ queryKey: ["suggestions"] });
  };
}
