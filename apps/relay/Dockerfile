FROM node:20-slim
WORKDIR /app
COPY package.json ./
RUN npm install --production
COPY src/ ./src/
EXPOSE 9200
CMD ["npx", "tsx", "src/index.ts"]
