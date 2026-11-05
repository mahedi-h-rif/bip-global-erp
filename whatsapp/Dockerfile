FROM node:24-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev
COPY server.mjs ./
ENV PORT=3210 WHATSAPP_AUTH_DIR=/data/auth
# Must be owned by node before VOLUME: a new named volume copies this ownership, and the session is written here.
RUN mkdir -p /data && chown node:node /data
VOLUME /data
EXPOSE 3210
USER node
CMD ["node", "server.mjs"]
