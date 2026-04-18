#!/bin/bash

# Script to empty an S3 bucket and upload files from a local directory
# Usage: ./upload-to-s3.sh <local-path> <s3-bucket>

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <local-path> <s3-bucket>"
    echo "Example: $0 ./ui/dist s3://my-bucket"
    exit 1
fi

LOCAL_PATH=$1
S3_BUCKET=$2

# Validate local path exists
if [ ! -d "$LOCAL_PATH" ]; then
    echo "Error: Local path '$LOCAL_PATH' does not exist or is not a directory"
    exit 1
fi

# Remove s3:// prefix if present
S3_BUCKET=${S3_BUCKET#s3://}

echo "Local path: $LOCAL_PATH"
echo "S3 bucket: s3://$S3_BUCKET"
echo ""
echo "WARNING: This will delete all files in s3://$S3_BUCKET"


# Empty the S3 bucket
echo "Emptying S3 bucket..."
aws s3 rm "s3://$S3_BUCKET" --recursive

if [ $? -ne 0 ]; then
    echo "Error: Failed to empty S3 bucket"
    exit 1
fi

echo "Bucket emptied successfully"
echo ""

# Upload files from local directory to S3 bucket
echo "Uploading files from $LOCAL_PATH to s3://$S3_BUCKET..."
aws s3 cp "$LOCAL_PATH" "s3://$S3_BUCKET" --recursive

if [ $? -ne 0 ]; then
    echo "Error: Failed to upload files to S3 bucket"
    exit 1
fi

echo ""
echo "Upload completed successfully"
