FROM node:18-alpine

WORKDIR /app

# Install exactly the locked dependencies; cache until package files change.
COPY package*.json ./
RUN npm ci

# Copy source code
COPY . .

EXPOSE 3000

# --host 0.0.0.0 is required so the dev server accepts connections from
# outside the container (Vite binds to localhost only by default)
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "3000"]
