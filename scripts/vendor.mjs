// Prépare les fichiers tiers servis avec le webmail :
// - postal-mime (décodage des mails dans le navigateur), regroupé en un seul fichier ;
// - la police Bricolage Grotesque (sous-ensemble latin).
// À relancer seulement pour mettre ces dépendances à jour : `npm run vendor`.
import { build } from "esbuild";
import { copyFile, mkdir } from "node:fs/promises";

await mkdir("public/vendor", { recursive: true });
await mkdir("public/fonts", { recursive: true });

await build({
  entryPoints: ["node_modules/postal-mime/dist/esm/postal-mime.js"],
  bundle: true,
  format: "esm",
  minify: true,
  target: "es2020",
  legalComments: "none",
  banner: { js: "/* postal-mime — licence MIT-0 — https://github.com/postalsys/postal-mime */" },
  outfile: "public/vendor/postal-mime.js",
});

await copyFile(
  "node_modules/@fontsource-variable/bricolage-grotesque/files/bricolage-grotesque-latin-wght-normal.woff2",
  "public/fonts/bricolage-grotesque.woff2"
);
console.log("Fichiers tiers prêts dans public/vendor et public/fonts.");
