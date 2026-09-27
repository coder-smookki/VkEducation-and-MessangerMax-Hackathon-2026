import { useId } from "react";
import "./brand.css";

/** Supplied brand artwork; application theme chooses the matching original. */
export function Brand({ className = "" }: { className?: string }) {
  return (
    <span className={`dompulse-brand ${className}`}>
      <img
        className="dompulse-logo-light"
        src="/brand/logo-light.svg"
        width="882"
        height="256"
        alt="ДомПульс"
        draggable={false}
      />
      <img
        className="dompulse-logo-dark"
        src="/brand/logo-dark.svg"
        width="882"
        height="256"
        alt="ДомПульс"
        draggable={false}
      />
    </span>
  );
}

export function BrandMark({ className = "" }: { className?: string }) {
  return (
    <img
      className={`dompulse-mark ${className}`}
      src="/brand/mark.svg"
      width="256"
      height="256"
      alt=""
      draggable={false}
    />
  );
}

/** Only render while an actual request is pending. No invented completion value. */
export function BrandSplash({
  label = "Загружаем ваш комфорт…",
  compact = false,
}: {
  label?: string;
  compact?: boolean;
}) {
  return (
    <div
      className={`brand-splash${compact ? " brand-splash-compact" : ""}`}
      role="status"
    >
      <div className="brand-splash-identity" aria-hidden="true">
        <BrandMark />
        <span className="brand-splash-title">
          Дом<span>Пульс</span>
        </span>
        {!compact && (
          <p>
            Мини-приложение
            <br />в MAX
          </p>
        )}
      </div>
      <div className="brand-splash-progress">
        <span className="brand-loading-track" aria-hidden="true">
          <span />
        </span>
        <p>{label}</p>
      </div>
      {!compact && (
        <span className="brand-splash-motto" aria-hidden="true">
          Люди · Дом · Решения · Вместе
        </span>
      )}
    </div>
  );
}

export type IllustrationKind =
  "house" | "document" | "success" | "empty" | "offline" | "analysis" | "error";

/** Decorative states are drawn locally, without a font, canvas or remote asset. */
export function StateIllustration({
  kind,
  className = "",
}: {
  kind: IllustrationKind;
  className?: string;
}) {
  const id = useId().replace(/:/g, "");
  const fill = (name: string) => `url(#${id}-${name})`;
  const paper = kind === "document" || kind === "analysis" || kind === "error";
  return (
    <svg
      className={`state-illustration state-illustration-${kind} ${className}`}
      viewBox="0 0 240 174"
      width="240"
      height="174"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id={`${id}-cloud`} x2="1" y2="1">
          <stop stopColor="#e0f5ff" />
          <stop offset="1" stopColor="#eee2ff" />
        </linearGradient>
        <linearGradient id={`${id}-blue`} x2="1" y2="1">
          <stop stopColor="#22b9ff" />
          <stop offset=".5" stopColor="#0c65ff" />
          <stop offset="1" stopColor="#8146ff" />
        </linearGradient>
        <linearGradient id={`${id}-house`} x2=".8" y2="1">
          <stop stopColor="#d9e8ff" />
          <stop offset=".45" stopColor="#a6bdff" />
          <stop offset="1" stopColor="#ac8aff" />
        </linearGradient>
        <linearGradient id={`${id}-paper`} x2=".8" y2="1">
          <stop stopColor="#fff" />
          <stop offset=".8" stopColor="#f6f6ff" />
          <stop offset="1" stopColor="#dce5ff" />
        </linearGradient>
        <linearGradient id={`${id}-green`} x2="1" y2="1">
          <stop stopColor="#23e4be" />
          <stop offset="1" stopColor="#00b98c" />
        </linearGradient>
        <linearGradient id={`${id}-pink`} x2="1" y2="1">
          <stop stopColor="#ff78ae" />
          <stop offset="1" stopColor="#ed277a" />
        </linearGradient>
      </defs>
      <g className="illustration-clouds">
        <path
          d="M32 108C6 72 37 34 72 50C82 11 133 15 149 49C182 25 221 50 213 86C243 123 207 156 172 145C152 172 114 162 99 143C64 161 20 143 32 108Z"
          fill={fill("cloud")}
          opacity=".75"
        />
        <circle cx="45" cy="143" r="13" fill="#ede6ff" />
        <circle cx="200" cy="57" r="8" fill="#d5ecff" />
      </g>
      {kind === "house" && (
        <g>
          <ellipse
            cx="122"
            cy="151"
            rx="53"
            ry="8"
            fill="#9ba8f4"
            opacity=".12"
          />
          <path
            d="M72 90L110 52Q120 42 130 52L168 90V137Q168 151 154 151H86Q72 151 72 137Z"
            fill={fill("house")}
          />
          <path
            d="M69 91L109 51Q120 39 131 51L171 91"
            fill="none"
            stroke="#d9e5ff"
            strokeWidth="6"
            strokeLinecap="round"
          />
          <rect x="104" y="99" width="13" height="14" rx="4" fill="#fff" />
          <rect x="123" y="99" width="13" height="14" rx="4" fill="#fff" />
          <rect x="104" y="119" width="13" height="14" rx="4" fill="#fff" />
          <rect x="123" y="119" width="13" height="14" rx="4" fill="#fff" />
          <ellipse
            cx="187"
            cy="119"
            rx="10"
            ry="19"
            transform="rotate(-35 187 119)"
            fill="#cedeff"
          />
        </g>
      )}
      {paper && (
        <g>
          <ellipse
            cx="119"
            cy="147"
            rx="49"
            ry="8"
            fill="#9ba8f4"
            opacity=".13"
          />
          <path
            d="M85 42H132L156 66V133Q156 144 145 144H85Q74 144 74 133V53Q74 42 85 42Z"
            fill={fill("paper")}
          />
          <path d="M132 42V60Q132 66 139 66H156" fill="#e2e9ff" />
          {kind === "analysis" ? (
            <path
              d="M87 101H99L108 81L119 117L129 96H143"
              fill="none"
              stroke={fill("blue")}
              strokeWidth="5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          ) : (
            <g stroke="#a7baff" strokeWidth="6" strokeLinecap="round">
              <path d="M90 76H129" />
              <path d="M90 92H137" />
              <path d="M90 108H123" />
            </g>
          )}
          <circle
            cx="157"
            cy="130"
            r="25"
            fill={fill(kind === "error" ? "pink" : "blue")}
            stroke="#fff"
            strokeWidth="3"
          />
          {kind === "document" && (
            <path
              d="M157 119V141M146 130H168"
              stroke="#fff"
              strokeWidth="4"
              strokeLinecap="round"
            />
          )}
          {kind === "analysis" && (
            <path
              d="M157 116V130L166 136"
              stroke="#fff"
              strokeWidth="4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          )}
          {kind === "error" && (
            <path
              d="M157 116V131M157 140V141"
              stroke="#fff"
              strokeWidth="5"
              strokeLinecap="round"
            />
          )}
        </g>
      )}
      {kind === "success" && (
        <g>
          <circle cx="120" cy="94" r="62" fill="#bcf8e8" opacity=".65" />
          <circle cx="120" cy="94" r="47" fill="#edfff9" />
          <circle cx="120" cy="94" r="34" fill={fill("green")} />
          <path
            d="M103 94L115 105L137 82"
            fill="none"
            stroke="#fff"
            strokeWidth="6"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
          <circle cx="182" cy="45" r="4" fill="#b49aff" />
          <circle cx="52" cy="58" r="3" fill="#30c9ff" />
          <circle cx="48" cy="122" r="4" fill="#ffd36d" />
        </g>
      )}
      {kind === "empty" && (
        <g>
          <ellipse
            cx="120"
            cy="145"
            rx="49"
            ry="7"
            fill="#9ba8f4"
            opacity=".14"
          />
          <path
            d="M86 85Q90 76 100 76H140Q150 76 154 85L169 119V134Q169 144 159 144H81Q71 144 71 134V119Z"
            fill={fill("house")}
          />
          <path
            d="M72 117H103L109 127H133L139 117H168"
            fill="none"
            stroke="#eef3ff"
            strokeWidth="5"
            strokeLinejoin="round"
          />
          <path
            d="M94 60L88 50M120 53V42M146 60L153 50"
            stroke={fill("blue")}
            strokeWidth="6"
            strokeLinecap="round"
          />
        </g>
      )}
      {kind === "offline" && (
        <g
          fill="none"
          stroke={fill("house")}
          strokeWidth="13"
          strokeLinecap="round"
        >
          <path d="M64 75Q120 26 176 75" />
          <path d="M84 96Q120 66 156 96" />
          <path d="M105 117Q120 106 135 117" />
          <circle cx="120" cy="137" r="7" fill={fill("blue")} stroke="none" />
          <circle
            cx="173"
            cy="125"
            r="21"
            fill={fill("pink")}
            stroke="#fff"
            strokeWidth="3"
          />
          <path
            d="M167 119L179 131M179 119L167 131"
            stroke="#fff"
            strokeWidth="4"
          />
        </g>
      )}
    </svg>
  );
}
