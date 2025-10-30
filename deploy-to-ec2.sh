#!/bin/bash
set -e

# Configuration
EC2_HOST="35.155.30.129"
EC2_USER="ec2-user"
PEM_FILE="/Users/yasser/Downloads/mjsip_s2s_gateway.pem"
REMOTE_DIR="/home/ec2-user/mjsip-s2s-voip-gateway"
LOCAL_PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "======================================"
echo "Build and Deploy to EC2"
echo "======================================"
echo ""

# Step 1: Build the Maven project locally
echo "[1/5] Building Maven project..."
mvn clean package -DskipTests
if [ ! -f "target/s2s-voip-gateway-0.6-SNAPSHOT.jar" ]; then
    echo "Error: JAR file not found after build!"
    exit 1
fi
echo "✓ Build successful"
echo ""

# Step 2: Create remote directory if it doesn't exist
echo "[2/5] Creating remote directory..."
ssh -i "$PEM_FILE" "$EC2_USER@$EC2_HOST" "mkdir -p $REMOTE_DIR"
echo "✓ Remote directory ready"
echo ""

# Step 3: Copy JAR file to EC2
echo "[3/5] Copying JAR file to EC2..."
scp -i "$PEM_FILE" "target/s2s-voip-gateway-0.6-SNAPSHOT.jar" "$EC2_USER@$EC2_HOST:$REMOTE_DIR/"
echo "✓ JAR file copied"
echo ""

# Step 4: Copy run script to EC2
echo "[4/5] Copying run script to EC2..."
scp -i "$PEM_FILE" "run.sh" "$EC2_USER@$EC2_HOST:$REMOTE_DIR/"
ssh -i "$PEM_FILE" "$EC2_USER@$EC2_HOST" "chmod +x $REMOTE_DIR/run.sh"
echo "✓ Run script copied"
echo ""

# Step 5: Copy environment file if it exists
echo "[5/5] Copying configuration files..."
if [ -f "environment" ]; then
    scp -i "$PEM_FILE" "environment" "$EC2_USER@$EC2_HOST:$REMOTE_DIR/"
    echo "✓ Environment file copied"
elif [ -f ".mjsip-ua" ]; then
    scp -i "$PEM_FILE" ".mjsip-ua" "$EC2_USER@$EC2_HOST:$REMOTE_DIR/"
    echo "✓ .mjsip-ua configuration copied"
else
    echo "⚠ Warning: No environment or .mjsip-ua file found. You'll need to configure the application on the EC2 instance."
fi
echo ""

echo "======================================"
echo "✓ Deployment Complete!"
echo "======================================"
echo ""
echo "To run the application on EC2:"
echo "  ssh -i $PEM_FILE $EC2_USER@$EC2_HOST"
echo "  cd $REMOTE_DIR"
echo "  ./run.sh"
echo ""
echo "To run in background:"
echo "  nohup ./run.sh > output.log 2>&1 &"
echo ""
