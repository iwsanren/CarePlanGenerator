FROM node:18-alpine

WORKDIR /app

# Copy package files first so `npm install` is cached unless deps change
COPY package*.json ./
RUN npm install

# Copy source code
COPY . .

EXPOSE 3000

# --host 0.0.0.0 is required so the dev server accepts connections from
# outside the container (Vite binds to localhost only by default)
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "3000"]
