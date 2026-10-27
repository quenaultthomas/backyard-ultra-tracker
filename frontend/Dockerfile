# syntax=docker/dockerfile:1

# Étape de build : compile l'application Angular en mode production.
FROM node:24.22.1-alpine3.24 AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Version affichée en pied de page : écrase src/app/version.ts (valeur `dev` en développement local).
ARG VERSION=dev
RUN printf "export const VERSION = '%s';\n" "$VERSION" > src/app/version.ts
RUN npm run build

# Étape d'exécution : Caddy sert le build et relaie /api vers le service api.
FROM caddy:2.11.7-alpine
# Le binaire caddy de l'image officielle porte la capacité cap_net_bind_service :
# un utilisateur non root peut écouter sur le port 80.
RUN addgroup -S web \
    && adduser -S -D -H -G web web \
    && chown -R web:web /data /config
COPY --chown=root:root Caddyfile /etc/caddy/Caddyfile
COPY --from=build --chown=root:root /app/dist/frontend/browser /srv
USER web
EXPOSE 80 443 443/udp
