# The public edge for the VPS: TLS, routing, rate limits and the Cloudflare
# rules. Configuration is baked in, so a change here rebuilds the image and the
# deploy replaces the container; nothing is edited on the server.
FROM nginx:stable-alpine

# openssl is only for the self-signed placeholder served before the first
# certificate is issued. The stock default.conf would answer for any hostname.
RUN apk add --no-cache openssl && rm /etc/nginx/conf.d/default.conf

COPY conf.d/ /etc/nginx/conf.d/
COPY snippets/ /etc/nginx/snippets/
COPY templates/ /etc/nginx/templates/
COPY entrypoint.d/ /docker-entrypoint.d/
