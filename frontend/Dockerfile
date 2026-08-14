FROM node:24-alpine AS build

WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine

RUN apk add --no-cache curl

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/blog-admin/browser /usr/share/nginx/html
COPY scripts/generate-config.sh /usr/local/bin/generate-config.sh

CMD ["sh", "-c", "CONFIG_FILE=/usr/share/nginx/html/config.js sh /usr/local/bin/generate-config.sh && exec nginx -g 'daemon off;'"]

EXPOSE 80
