package com.studio.smartPhotoService.services;

import com.studio.smartPhotoService.Utilities.FilePathCamouflage;
import com.studio.smartPhotoService.entities.Wedding;
import com.studio.smartPhotoService.entities.WeddingMember;
import com.studio.smartPhotoService.exceptions.WeddingMemberDoesNotExistsException;
import com.studio.smartPhotoService.exceptions.WeddingObjectAlreadyExistsException;
import com.studio.smartPhotoService.repository.WeddingMemberRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;


@Service
public class WeddingMemberService {

    @Autowired
    private WeddingMemberRepo weddingMemberRepo;

    @Autowired
    private WeddingService weddingService;

    @Value("${file.upload-dir}")
    private String photosUploadFilePath;

    public WeddingMember createWeddingMember(WeddingMember weddingMember) {
        // Wedding Member has the code attached to WeddingMember
        // Fetch Wedding Object with code
        // Set Wedding Obj to Wedding member and save
        weddingMember.setAttendsWeddingSet(
                weddingMember.getAttendsWeddingSet().stream().map(wedding ->
                                weddingService.getWeddingForGivenWeddingCode(wedding.getWeddingUniqueCode()))
                        .collect(Collectors.toSet())
        );

        return weddingMemberRepo.save(weddingMember);
    }

    public WeddingMember getWeddingMemberById(Long weddingMemberId) {
        return this.weddingMemberRepo.findById(weddingMemberId).orElseThrow(() -> new WeddingMemberDoesNotExistsException("Wedding member with Id %s does not exists".formatted(weddingMemberId)));
    }

    public WeddingMember registerToWeddingObjectByGivenId(Long weddingMemberId, String uniqueCode) {
        WeddingMember weddingMember = this.getWeddingMemberById(weddingMemberId);
        Wedding weddingObj = this.weddingService.getWeddingForGivenWeddingCode(uniqueCode);

        // If WeddingObject is already added then it throw an Exception
        if (!weddingMember.getAttendsWeddingSet().add(weddingObj)) {
            throw new WeddingObjectAlreadyExistsException("Wedding Object with code %s already registered to Wedding Member with id %s".formatted(uniqueCode, weddingMemberId));
        }
        return this.weddingMemberRepo.save(weddingMember);
    }

    /*
    Method to add file paths to weddingMember entity
     */
    public List<String> addPhotosPathToWeddingMember(WeddingMember weddingMember, List<String> photosPath) {
        weddingMember.getWeddingMemberImagePathList().addAll(photosPath);
        return this.weddingMemberRepo.save(weddingMember).getWeddingMemberImagePathList();
    }

    /*
     This method first of saves all the multipart file to desired location and adds photos Path to Wedding Member
     */
    public List<List<String>> uploadPhotosAndAddPhotosPathToWeddingMember(Long weddingMemberId, List<MultipartFile> files) throws MalformedURLException {
        WeddingMember weddingMember = this.getWeddingMemberById(weddingMemberId);

        // copies the image files to photosUploadFilePath location
        // also tracks what all files are successfully added and what are not
        // also names the file according to wedding member's Id + Name + random Integer
        List<String> filesNotAdded = new ArrayList<>();
        List<String> filesToBeAdded = new ArrayList<>();

        for (MultipartFile file : files) {

            Path filePath = Paths.get(this.photosUploadFilePath + FilePathCamouflage.generateRandomFileName(weddingMember.getWeddingMemberId(), weddingMember.getWeddingMemberName()) + FilePathCamouflage.getExtension(file.getOriginalFilename()));

            try {
                Files.copy(file.getInputStream(), filePath);
                filesToBeAdded.add(filePath.toAbsolutePath().toString());

            } catch (IOException e) {
                e.printStackTrace();
                filesNotAdded.add(file.getOriginalFilename());
            }
        }

        // add the collected file path to WeddingMember Object
        List<String> filesAdded = this.addPhotosPathToWeddingMember(weddingMember, filesToBeAdded);

        return Arrays.asList(filesAdded, filesNotAdded);
    }

    /**
     * <p>
     * Get all uploaded photos of a Wedding Member into ByteArrayResource format
     * </p>
     *
     * @param weddingMemberId
     * @return List of ByteArrayResource
     */
    public List<ByteArrayResource> getAllPhotosByWeddingMemberId(Long weddingMemberId) {
        WeddingMember weddingMember = this.getWeddingMemberById(weddingMemberId);
        List<ByteArrayResource> weddingMemberPhotosList = new ArrayList<>();

        weddingMember.getWeddingMemberImagePathList().forEach(imagePath -> {
            try {
                ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(Path.of(imagePath)));
                weddingMemberPhotosList.add(resource);
            } catch (NoSuchFileException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        return weddingMemberPhotosList;

    }

    /**
     * Updates Attended Wedding Set of Wedding Member and also de-links removed Wedding Objects from WeddingMember
     *
     * @param memberId
     * @param toRegisterWeddingUniqueCodeList
     * @param toUnRegisterWeddingIdList
     * @return {@link WeddingMember}
     */
    public WeddingMember updateAttendedWeddingSet(Long memberId, List<String> toRegisterWeddingUniqueCodeList, List<Long> toUnRegisterWeddingIdList) {
        WeddingMember weddingMember = this.getWeddingMemberById(memberId);

        // remove all WeddingIds to Un-Register and removing link between WeddingMember and Wedding

        Set<Wedding> weddingsToRemove = new HashSet<>();

        weddingMember.getAttendsWeddingSet().forEach(wedding -> {
            if (toUnRegisterWeddingIdList.contains(wedding.getWeddingId())) {
                wedding.removeWeddingMember(weddingMember);
                weddingsToRemove.add(wedding);
            }
        });
        weddingMember.getAttendsWeddingSet().removeAll(weddingsToRemove);

        // add new wedding unique codes
        toRegisterWeddingUniqueCodeList.forEach(uniqueCode -> registerToWeddingObjectByGivenId(memberId, uniqueCode));
        // save Wedding Member and save
        return this.weddingMemberRepo.save(weddingMember);

    }

    /**
     * This method updates profile of Existing Wedding Member such as Member Name, Member Email-Id, Member Relation
     *
     * @param updatedWeddingMember
     * @return {@link WeddingMember}
     */
    public WeddingMember updateWeddingMemberProfile(WeddingMember updatedWeddingMember) {
        WeddingMember existingWeddingMember = this.getWeddingMemberById(updatedWeddingMember.getWeddingMemberId());

        //start updating
        if (updatedWeddingMember.getWeddingMemberName() != null && !updatedWeddingMember.getWeddingMemberName().isBlank()) {
            existingWeddingMember.setWeddingMemberName(updatedWeddingMember.getWeddingMemberName());
        }
        if (updatedWeddingMember.getWeddingMemberEmailId() != null && !updatedWeddingMember.getWeddingMemberEmailId().isBlank()) {
            existingWeddingMember.setWeddingMemberEmailId(updatedWeddingMember.getWeddingMemberEmailId());
        }
        if (updatedWeddingMember.getWeddingMemberRelation() != null && !updatedWeddingMember.getWeddingMemberRelation().isBlank()) {
            existingWeddingMember.setWeddingMemberRelation(updatedWeddingMember.getWeddingMemberRelation());
        }
        return this.weddingMemberRepo.save(existingWeddingMember);
    }

    public boolean deleteWeddingMemberById(long memberId) {
        WeddingMember weddingMember = this.getWeddingMemberById(memberId);

        // remove references from every Wedding Object present in WeddingMember
        weddingMember.getAttendsWeddingSet().forEach(wedding -> wedding.removeWeddingMember(weddingMember));

        this.weddingMemberRepo.delete(weddingMember);
        return true;
    }
}
