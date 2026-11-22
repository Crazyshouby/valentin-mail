# Courrier — boîte mail maison sur Cloudflare

Une boîte mail pour `valentinlarge.com`, hébergée sur ton compte Cloudflare, avec un webmail à toi.

- **Réception** : Email Routing remet chaque mail au Worker, qui range le message brut dans R2 et une ligne d'index dans D1.
- **Lecture** : le webmail (dossier `public/`) décode les messages dans le navigateur.
- **Envoi** : le Worker passe par Resend, puis garde une copie dans « Envoyés ».

## Déjà fait sur ton compte Cloudflare

| Ressource | Nom | État |
| --- | --- | --- |
| Base D1 | `valentin-mail` | créée, tables et index en place |
| Bucket R2 | `valentin-mail-raw` | créé, vide |

Leurs identifiants sont déjà dans `wrangler.jsonc`. Rien d'autre n'a été modifié sur le compte : ni DNS, ni Email Routing, ni Worker.

## Mise en ligne sans ordinateur (GitHub + tableau de bord Cloudflare)

Tout se fait depuis le navigateur du téléphone.

1. **Déployer** : dans le tableau de bord Cloudflare, **Workers & Pages > Create application > GitHub**, choisis le dépôt `valentin-mail`, puis **Save and Deploy**. Garde le nom `valentin-mail` : il doit être identique à celui de `wrangler.jsonc`. Chaque nouvelle modification du dépôt sera ensuite redéployée toute seule.
2. **Définir le mot de passe du webmail** : ouvre le Worker `valentin-mail`, **Settings > Variables and Secrets**, ajoute un **Secret** nommé `APP_PASSWORD`. Choisis une phrase longue (16 caractères ou plus) : c'est la seule protection de ta boîte.
3. **Activer la réception** : **Compute > Email Service > Email Routing**, choisis `valentinlarge.com` et suis l'activation (Cloudflare ajoute lui-même les enregistrements DNS). Puis, dans **Routing Rules**, active la règle **Catch-all** avec l'action **Send to a Worker** et le Worker `valentin-mail`.
4. **Brancher l'envoi** :
   - crée un compte sur [resend.com](https://resend.com), ajoute le domaine `valentinlarge.com` ;
   - recopie dans le DNS Cloudflare les enregistrements que Resend affiche, puis attends que le domaine passe à « vérifié » ;
   - crée une clé API limitée à l'envoi, et ajoute-la au Worker comme **Secret** nommé `RESEND_API_KEY`.
5. **Essayer** : depuis une autre adresse, écris à n'importe quelle adresse `@valentinlarge.com`. Le message doit apparaître dans le webmail. Réponds-y pour vérifier l'envoi.

Pour changer l'adresse d'expédition, modifie `MAIL_FROM` dans `wrangler.jsonc` (directement sur GitHub) : le Worker se redéploie.

## Mise en ligne avec un ordinateur (Wrangler)

Avec Node.js 22.12 ou plus récent : `npm install`, `npx wrangler login`, `npx wrangler deploy`, puis `npx wrangler secret put APP_PASSWORD` et `npx wrangler secret put RESEND_API_KEY`. Les étapes Email Routing et Resend sont les mêmes que ci-dessus.

### Faire arriver tes mails Gmail dans cette boîte

Dans Gmail, version web pour ordinateur (sur téléphone : ouvre mail.google.com dans le navigateur et demande la « version pour ordinateur ») : **Paramètres > Transfert et POP/IMAP > Ajouter une adresse de transfert**, et indique ton adresse `@valentinlarge.com`. Le mail de confirmation de Google arrive dans le webmail : valide-le. Active ensuite le transfert en choisissant de **conserver la copie Gmail**. Tant que cette boîte est récente, Gmail reste ton filet de sécurité.

À répéter pour chaque compte Gmail.

### Reprendre les anciens messages de Gmail

Le transfert ne concerne que les messages à venir. Pour les anciens, ouvre `/import` sur l'adresse du webmail, dans le navigateur où tu es connecté à la boîte. La page lit Gmail en lecture seule et remet chaque message à la boîte, avec sa date, son état lu ou non lu, sa conversation et ses dossiers.

- **Réglage unique** : la page demande un « ID client » Google et explique comment le créer (console Google Cloud : activer l'API Gmail, déclarer ton adresse comme utilisateur test, créer un client « Application Web » avec l'adresse de redirection que la page affiche). Cet identifiant n'est pas un secret.
- **Ce qui est importé** : ce que tu as rangé dans un dossier, tes messages envoyés, et le reste de ta réception. Les pubs, réseaux sociaux et notifications que tu n'as rangés nulle part restent chez Google, comme le spam et la corbeille.
- **Sans doublon** : un message déjà arrivé par le transfert est reconnu à son identifiant ; il gagne seulement ses dossiers.
- **Reprise et annulation** : l'import peut être interrompu et relancé, et « Défaire l'import » efface tout ce qu'il a apporté, rien d'autre.
- **Rythme** : l'import s'arrête de lui-même à 60 000 écritures par jour dans la base, pour ne jamais bloquer la réception du courrier (le forfait gratuit en accepte 100 000). Il reprend le lendemain.

### Dossiers personnels

En plus de Réception, Envoyés, Archives et Corbeille, la boîte accepte tes propres dossiers. Un message peut être rangé dans plusieurs ; « Finances/Énergie » crée un sous-dossier. Une règle de tri peut ranger à l'arrivée, en laissant ou non le message en réception. Les dossiers se gèrent depuis l'appli Android ; le webmail ne les affiche pas encore.

### Options

- **Ouvrir le webmail sur ton domaine** : dans `wrangler.jsonc`, retire les `//` devant la ligne `routes`, puis redéploie. Le webmail répond alors sur `courrier.valentinlarge.com`.
- **L'installer sur le téléphone** : ouvre le webmail dans le navigateur du téléphone, puis « Ajouter à l'écran d'accueil ».
- **Ne recevoir que certaines adresses** : au lieu de la règle Catch-all, crée dans Email Routing une règle par adresse, chacune avec l'action **Send to a Worker**.

## Ce que fait la version 1

- Boîte de réception, Envoyés, Archives, Corbeille.
- Lecture des mails texte et HTML, images intégrées, pièces jointes à télécharger.
- Recherche par expéditeur, destinataire ou objet dans le dossier ouvert.
- Écrire et répondre (la réponse se rattache à la conversation chez ton correspondant).
- Lu / non lu, archiver, corbeille, suppression définitive avec confirmation.

## Limites à connaître

- **Envoi en texte seul, sans pièce jointe.** Les pièces jointes reçues se lisent ; en envoyer demandera une version 2.
- **Quota Resend gratuit** : 3 000 mails par mois, 100 par jour (vérifié le 21 novembre 2026).
- **Mails de plus de 25 Mio** : refusés par Cloudflare avant d'atteindre la boîte.
- **Pas de dossier spam.** Cloudflare rejette les mails non authentifiés ; le reste arrive tel quel.
- **Lecture dans le webmail uniquement** : pas d'IMAP, donc pas d'application mail classique, et pas de notification.
- **Images distantes masquées** par défaut dans les mails HTML (anti-pistage) ; un bouton les affiche.
- **Quotas gratuits Cloudflare** : 10 Go dans R2, 5 Go et 100 000 écritures par jour dans D1, 100 000 requêtes par jour pour le Worker. Si le quota D1 du jour est atteint, la boîte se bloque jusqu'à minuit UTC.
- **Tu es l'administrateur.** Si le Worker ne peut pas ranger un message, il renvoie l'erreur à Cloudflare au lieu de l'ignorer, mais personne ne surveille à ta place.

## Sécurité

- Le webmail est protégé par un mot de passe unique, stocké comme secret Cloudflare. La session dure 30 jours ; changer le mot de passe déconnecte tous les appareils.
- Après 8 mots de passe faux en un quart d'heure depuis la même adresse IP, la connexion est bloquée 15 minutes pour cette adresse.
- Le HTML des mails est nettoyé (scripts, formulaires, redirections), puis affiché dans un cadre isolé qui ne peut ni exécuter de script ni charger de ressource distante.

## En cas de problème

- **Journal en direct** : `npx wrangler tail`.
- **Un mail n'arrive pas** : regarde le journal d'activité d'Email Routing dans le tableau de bord, il indique si le message a été remis au Worker.
- **« Le service d'envoi a refusé le message »** : le webmail affiche la raison donnée par Resend, le plus souvent un domaine pas encore vérifié.

## Tester sur ton ordinateur

```sh
cp .dev.vars.example .dev.vars   # mot de passe et clé de test
npm run db:local                 # crée les tables en local
npm run dev                      # http://localhost:8787
```

Pour simuler l'arrivée d'un mail (le fichier doit contenir un en-tête `Message-ID`) :

```sh
curl -X POST 'http://localhost:8787/cdn-cgi/local/email?from=ami@exemple.fr&to=bonjour@valentinlarge.com' \
  --data-binary @message.eml
```

## Fichiers

| Fichier | Rôle |
| --- | --- |
| `src/worker.js` | Réception des mails, API du webmail, envoi par Resend |
| `public/` | Le webmail : page, styles, script, icônes |
| `public/vendor/postal-mime.js` | Décodeur de mails (bibliothèque postal-mime, licence MIT-0) |
| `public/fonts/` | Police Bricolage Grotesque (licence SIL OFL) |
| `public/import.html`, `public/import.js` | Page d'import depuis Gmail |
| `schema.sql` | Tables D1 (déjà appliqué en production) |
| `wrangler.jsonc` | Configuration du déploiement |
| `scripts/vendor.mjs` | Régénère les fichiers tiers (`npm run vendor`) |
