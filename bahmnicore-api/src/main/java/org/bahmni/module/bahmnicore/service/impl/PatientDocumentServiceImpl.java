package org.bahmni.module.bahmnicore.service.impl;

import liquibase.util.file.FilenameUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bahmni.module.bahmnicore.BahmniCoreException;
import org.bahmni.module.bahmnicore.bahmniexceptions.VideoFormatNotSupportedException;
import org.bahmni.module.bahmnicore.model.VideoFormats;
import org.bahmni.module.bahmnicore.properties.BahmniCoreProperties;
import org.bahmni.module.bahmnicore.service.PatientDocumentService;
import org.imgscalr.Scalr;
import org.openmrs.module.webservices.rest.web.RestUtil;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.xml.bind.DatatypeConverter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

@Service
@Lazy
public class PatientDocumentServiceImpl implements PatientDocumentService {
    private static final String PDF = "pdf";
    private Log log = LogFactory.getLog(PatientDocumentServiceImpl.class);
    private static final String patientImagesFormat = "jpeg";
    private final Integer NO_OF_PATIENT_FILE_IN_A_DIRECTORY = 100;
    private final String VIDEO_FILE_TYPE = "video";

    @Override
    public void saveImage(String patientIdentifier, String image) {
        try {
            if (image == null || image.isEmpty()) return;

            File outputFile = new File(String.format("%s/%s.%s", BahmniCoreProperties.getProperty("bahmnicore.images.directory"), patientIdentifier, patientImagesFormat));
            saveDocumentInFile(image, patientImagesFormat, outputFile, "image");
        } catch (IOException e) {
            throw new BahmniCoreException("[%s] : Could not save patient image", e);
        }
    }

    @Override
    public String saveDocument(Integer patientId, String encounterTypeName, String images, String format, String fileType) {
        try {
            if (images == null || images.isEmpty()) return null;

            String basePath = BahmniCoreProperties.getProperty("bahmnicore.documents.baseDirectory");
            String relativeFilePath = createFilePath(basePath, patientId, encounterTypeName, format);

            File outputFile = new File(String.format("%s/%s", basePath, relativeFilePath));
            saveDocumentInFile(images, format, outputFile, fileType);

            return relativeFilePath;

        } catch (IOException e) {
            throw new BahmniCoreException("[%s] : Could not save patient Document ", e);
        }
    }

    private String createFileName(Integer patientId, String encounterTypeName, Object format) {
        String uuid = UUID.randomUUID().toString();
        return String.format("%s-%s-%s.%s", patientId, encounterTypeName, uuid, format);
    }

    protected String createFilePath(String basePath, Integer patientId, String encounterTypeName, String format) {
        String fileName = createFileName(patientId, encounterTypeName, format);
        String documentDirectory = findDirectoryForDocumentsByPatientId(patientId);
        String absoluteFilePath = String.format("%s/%s", basePath, documentDirectory);
        File absoluteFileDirectory = new File(absoluteFilePath);
        if (!absoluteFileDirectory.exists()) {
            absoluteFileDirectory.mkdirs();
        }
        return String.format("%s/%s", documentDirectory, fileName);
    }

    private String findDirectoryForDocumentsByPatientId(Integer patientId) {
        Integer directory = (patientId / NO_OF_PATIENT_FILE_IN_A_DIRECTORY + 1) * NO_OF_PATIENT_FILE_IN_A_DIRECTORY;
        return directory.toString();
    }

    private void saveDocumentInFile(String document, String format, File outputFile, String fileType) throws IOException {
        log.info(String.format("Creating patient document of format %s at %s", format, outputFile));
        byte[] decodedBytes = DatatypeConverter.parseBase64Binary(document);
        if (fileType.equals(VIDEO_FILE_TYPE)) {
            if (!isVideoFormatSupported(format)) {
                throw new VideoFormatNotSupportedException(String.format("The video format '%s' is not supported. Supported formats are %s", format, Arrays.toString(VideoFormats.values())));
            }
            FileUtils.writeByteArrayToFile(outputFile, decodedBytes);
        } else if (PDF.equals(format)) {
            FileUtils.writeByteArrayToFile(outputFile, decodedBytes);
        } else {
            BufferedImage bufferedImage = ImageIO.read(new ByteArrayInputStream(decodedBytes));
            ImageIO.write(bufferedImage, format, outputFile);
            createThumbnail(bufferedImage, outputFile);
            bufferedImage.flush();
            log.info(String.format("Successfully created patient image at %s", outputFile));
        }
    }

    private boolean isVideoFormatSupported(String format) {
        return VideoFormats.isFormatSupported(format);
    }

    private void createThumbnail(BufferedImage image, File outputFile) throws IOException {
        String nameWithoutExtension = FilenameUtils.removeExtension(outputFile.getAbsolutePath());
        String extension = FilenameUtils.getExtension(outputFile.getAbsolutePath());
        File thumbnailFile = new File(String.format("%s_thumbnail.%s", nameWithoutExtension, extension));
        BufferedImage reSizedImage = Scalr.resize(image, 100);
        ImageIO.write(reSizedImage, extension, thumbnailFile);
        reSizedImage.flush();
    }

    @Override
    public ResponseEntity<Object> retriveImage(String patientUuid) {
        File file = getPatientImageFile(patientUuid);
        return readImage(file);
    }

    private File getPatientImageFile(String patientUuid) {
        File file = new File(String.format("%s/%s.%s", BahmniCoreProperties.getProperty("bahmnicore.images.directory"), patientUuid, patientImagesFormat));
        if (file.exists() && file.isFile()) {
            return file;
        }
        return new File(BahmniCoreProperties.getProperty("bahmnicore.images.directory.defaultImage"));
    }

    private ResponseEntity<Object> readImage(File file) {
        byte[] fileByteArray = new byte[(int) file.length()];
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            fileInputStream.read(fileByteArray);
            return new ResponseEntity<Object>(fileByteArray, HttpStatus.OK);
        } catch (FileNotFoundException e) {
            return new ResponseEntity<Object>(RestUtil.wrapErrorResponse(e, e.getMessage()), HttpStatus.NOT_FOUND);
        } catch (IOException e) {
            return new ResponseEntity<Object>(RestUtil.wrapErrorResponse(e, e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
