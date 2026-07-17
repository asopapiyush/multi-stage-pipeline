FROM node:20-alpine
WORKDIR /app
COPY . .
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -q --spider http://localhost:3000/ || exit 1
CMD ["node", "server.js"]
