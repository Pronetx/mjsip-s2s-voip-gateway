#!/bin/bash
set -e

# Configuration
EC2_HOST="35.155.30.129"
EC2_USER="ec2-user"
PEM_FILE="/Users/yasser/Downloads/mjsip_s2s_gateway.pem"
REMOTE_DIR="/home/ec2-user/mjsip-s2s-voip-gateway"

echo "======================================"
echo "Setup Nova VoIP Gateway as Service"
echo "======================================"
echo ""

# Step 1: Create .mjsip-ua configuration file
echo "[1/4] Creating .mjsip-ua configuration file..."
cat > /tmp/.mjsip-ua << 'EOF'
#######################################################################################
# Configuration for the mjsip user agent in TRUNK MODE (inbound calls only)
#######################################################################################

# Display name for the user
display-name=Nova Gateway

# User's name - used in From header
sip-user=gateway

# SIP server (update with your SIP server)
registrar=your_sip_server

# Authentication credentials (update with your credentials)
auth_user=your_username
auth_realm=your_realm
auth_passwd=your_password

# TRUNK MODE SETTINGS
# Disable registration - gateway will only accept inbound calls
do_register=no

# Via address - EC2 Elastic IP
via-addr=35.155.30.129

# SIP listening port
host-port=5060

# RTP media port range (must match security group rules)
media-port=10000
port-count=10000

# Non-interactive mode (required for running as service)
no-prompt=yes

# Debug SIP traffic
log-all-packets=yes

# Media configuration
media=audio 4000 RTP/AVP { 0 PCMU 8000 160 }
EOF

# Step 2: Upload configuration and service files
echo "[2/4] Uploading configuration files to EC2..."
scp -i "$PEM_FILE" /tmp/.mjsip-ua "$EC2_USER@$EC2_HOST:$REMOTE_DIR/"
scp -i "$PEM_FILE" nova-voip-gateway.service "$EC2_USER@$EC2_HOST:/tmp/"
rm /tmp/.mjsip-ua
echo "✓ Configuration files uploaded"
echo ""

# Step 3: Install and enable the service
echo "[3/4] Installing systemd service..."
ssh -i "$PEM_FILE" "$EC2_USER@$EC2_HOST" << 'ENDSSH'
sudo mv /tmp/nova-voip-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable nova-voip-gateway.service
echo "✓ Service installed and enabled"
ENDSSH
echo ""

# Step 4: Start the service
echo "[4/4] Starting the service..."
ssh -i "$PEM_FILE" "$EC2_USER@$EC2_HOST" << 'ENDSSH'
sudo systemctl start nova-voip-gateway.service
sleep 2
sudo systemctl status nova-voip-gateway.service --no-pager
ENDSSH
echo ""

echo "======================================"
echo "✓ Service Setup Complete!"
echo "======================================"
echo ""
echo "IMPORTANT: Edit the .mjsip-ua file on the EC2 instance with your SIP credentials:"
echo "  ssh -i $PEM_FILE $EC2_USER@$EC2_HOST"
echo "  nano $REMOTE_DIR/.mjsip-ua"
echo "  # Update: registrar, auth_user, auth_realm, auth_passwd"
echo "  sudo systemctl restart nova-voip-gateway.service"
echo ""
echo "Service management commands:"
echo "  Status:  sudo systemctl status nova-voip-gateway"
echo "  Logs:    sudo journalctl -u nova-voip-gateway -f"
echo "  Restart: sudo systemctl restart nova-voip-gateway"
echo "  Stop:    sudo systemctl stop nova-voip-gateway"
echo ""
