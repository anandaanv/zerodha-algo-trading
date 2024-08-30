// image-browser.js
function fetchImages() {
    const directory = document.getElementById("directoryInput").value;
    if (!directory) {
        alert("Please enter a directory path");
        return;
    }

    // Fetch the list of image filenames from the server
    fetch(`/images?directory=${encodeURIComponent(directory)}`)
        .then(response => response.json())
        .then(imageFiles => {
            const imageSelector = document.getElementById("imageSelector");
            imageSelector.innerHTML = "";  // Clear previous options

            // Populate the select dropdown with image filenames
            imageFiles.forEach((file) => {
                const option = document.createElement("option");
                option.value = file;
                option.textContent = file;
                imageSelector.appendChild(option);
            });
        })
        .catch(error => console.error('Error fetching image list:', error));
}

function loadImage() {
    const directory = document.getElementById("directoryInput").value;
    const selectedImage = document.getElementById("imageSelector").value;
    const displayedImage = document.getElementById("displayedImage");

    if (!selectedImage) {
        alert("Please select an image");
        return;
    }

    displayedImage.src = `/images/view?directory=${encodeURIComponent(directory)}&filename=${encodeURIComponent(selectedImage)}`;
    displayedImage.alt = selectedImage;
}
