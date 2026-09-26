import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { QueryClientProvider } from "@tanstack/react-query";
import { createHashRouter, RouterProvider } from "react-router-dom";
import { App, AccessPage, LogoutPage } from "./App";
import { queryClient } from "./api/queries";
import {
  CasePage,
  CasesPage,
  ConfirmCasePage,
  DraftPage,
  NewCasePage,
} from "./features/cases";
import { ItemActionPage } from "./features/item-actions";
import { NewReportPage, ReportPage } from "./features/reports";
import { startCaseId } from "./platform/max";
import "./styles.css";

// Preserve explicit browser links. start_param only selects a route; Java still checks access.
if (!location.hash && startCaseId())
  history.replaceState(null, "", `#/cases/${startCaseId()}`);
const router = createHashRouter([
  {
    element: <App />,
    children: [
      { path: "/", element: <CasesPage /> },
      { path: "/access", element: <AccessPage /> },
      { path: "/logout", element: <LogoutPage /> },
      { path: "/new", element: <NewCasePage /> },
      { path: "/cases/:caseId", element: <CasePage /> },
      { path: "/cases/:caseId/edit", element: <DraftPage /> },
      { path: "/cases/:caseId/source", element: <DraftPage sourceOnly /> },
      { path: "/cases/:caseId/review", element: <ConfirmCasePage /> },
      {
        path: "/cases/:caseId/items/:itemId/:action",
        element: <ItemActionPage />,
      },
      { path: "/cases/:caseId/reports/new", element: <NewReportPage /> },
      { path: "/cases/:caseId/reports/:reportId", element: <ReportPage /> },
      {
        path: "*",
        element: (
          <section className="card">
            <h1>Страница не найдена</h1>
            <a href="#/">К обращениям</a>
          </section>
        ),
      },
    ],
  },
]);
createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
);
