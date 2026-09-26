FROM nginx:stable-alpine
COPY templates/waypoint.conf.template /etc/nginx/templates/waypoint.conf.template
COPY entrypoint.d/25-conditional-tls.sh /docker-entrypoint.d/25-conditional-tls.sh
