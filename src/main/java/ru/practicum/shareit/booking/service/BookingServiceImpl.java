package ru.practicum.shareit.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.shareit.booking.controller.RequestState;
import ru.practicum.shareit.booking.dto.BookingDto;
import ru.practicum.shareit.booking.dto.CreateBookingDto;
import ru.practicum.shareit.booking.dto.GetBookingRequest;
import ru.practicum.shareit.booking.mapper.BookingMapper;
import ru.practicum.shareit.booking.model.Booking;
import ru.practicum.shareit.booking.model.Status;
import ru.practicum.shareit.booking.repository.BookingRepository;
import ru.practicum.shareit.exception.BookingStatusException;
import ru.practicum.shareit.exception.ItemUnavailableException;
import ru.practicum.shareit.exception.NotFoundException;
import ru.practicum.shareit.item.model.Item;
import ru.practicum.shareit.item.repository.ItemRepository;
import ru.practicum.shareit.user.model.User;
import ru.practicum.shareit.user.repository.UserRepository;
import ru.practicum.shareit.user.service.UserService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

    private static final Sort SORT_BY_START_DESC = Sort.by(Sort.Direction.DESC, "start");

    private final BookingRepository bookingRepository;
    private final ItemRepository itemRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final BookingMapper bookingMapper;

    @Transactional
    @Override
    public BookingDto create(CreateBookingDto dto, Long userId) {
        Item item = itemRepository.findById(dto.getItemId()).orElseThrow(() -> {
            throw new NotFoundException("Item with id " + dto.getItemId() + " not found");
        });
        if (item.getOwner().getId().equals(userId)) {
            throw new NotFoundException("Owner of the item cannot create a booking");
        }
        if (Boolean.FALSE.equals(item.getAvailable())) {
            throw new ItemUnavailableException("Item with id " + dto.getItemId() + " not available");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> {
            throw new NotFoundException("User with id " + userId + " not found");
        });
        Booking booking = bookingMapper.toEntity(dto);
        booking.setItem(item);
        booking.setBooker(user);
        Booking savedBooking = bookingRepository.save(booking);
        log.info("Booking with id " + savedBooking.getId() + " created");
        return bookingMapper.toBookingDto(savedBooking);
    }

    @Transactional
    @Override
    public BookingDto setApprove(Long id, Boolean isApproved, Long ownerId) {
        Booking booking = bookingRepository.findById(id).orElseThrow(() -> {
            throw new NotFoundException("Booking with id " + id + " not found");
        });
        if (!booking.getItem().getOwner().getId().equals(ownerId)) {
            throw new NotFoundException("User with id " + ownerId + " has no rights to change this item");
        }
        if (booking.getStatus() != Status.WAITING) {
            throw new BookingStatusException("Booking status is already set");
        }
        if (isApproved) {
            booking.setStatus(Status.APPROVED);
        } else {
            booking.setStatus(Status.REJECTED);
        }
        log.info("Booking status with id " + booking.getId() + " changed to " + booking.getStatus());
        return bookingMapper.toBookingDto(booking);
    }

    @Override
    public BookingDto getById(Long id, Long userId) {
        Booking booking = bookingRepository.findById(id).orElseThrow(() -> {
            throw new NotFoundException("Booking with id " + id + " not found");
        });
        if (booking.getBooker().getId().equals(userId) || booking.getItem().getOwner().getId().equals(userId)) {
            return bookingMapper.toBookingDto(booking);
        } else {
            throw new NotFoundException("User with id " + userId + " has no rights to view this booking");
        }
    }

    @Override
    public List<BookingDto> getAllForUserByState(GetBookingRequest request) {
        Long userId = request.getUserId();
        userService.checkUserExist(userId);
        PageRequest pageRequest = PageRequest.of(
                (request.getFrom() / request.getSize()), request.getSize(), SORT_BY_START_DESC);
        Page<Booking> page = getPageOfBookingsForUserByState(userId, pageRequest, request.getState());
        return bookingMapper.toBookingDtoList(page.getContent());
    }

    @Override
    public List<BookingDto> getAllForOwnerByState(GetBookingRequest request) {
        Long ownerId = request.getUserId();
        userService.checkUserExist(ownerId);
        PageRequest pageRequest = PageRequest.of(
                (request.getFrom() / request.getSize()), request.getSize(), SORT_BY_START_DESC);
        Page<Booking> page = getPageOfBookingsForOwnerByState(ownerId, pageRequest, request.getState());
        return bookingMapper.toBookingDtoList(page.getContent());
    }

    private Page<Booking> getPageOfBookingsForUserByState(Long userId, Pageable pageable, RequestState state) {
        Map<RequestState, Supplier<Page<Booking>>> strategyMap = Map.of(
                RequestState.ALL, () -> bookingRepository.findByBookerId(userId, pageable),
                RequestState.CURRENT, () -> bookingRepository.findByBookerIdAndStartIsBeforeAndEndIsAfter(
                        userId, LocalDateTime.now(), LocalDateTime.now(), pageable),
                RequestState.FUTURE, () -> bookingRepository.findByBookerIdAndStartIsAfterAndEndIsAfter(
                        userId, LocalDateTime.now(), LocalDateTime.now(), pageable),
                RequestState.PAST, () -> bookingRepository.findByBookerIdAndStartIsBeforeAndEndIsBefore(
                        userId, LocalDateTime.now(), LocalDateTime.now(), pageable),
                RequestState.WAITING, () -> bookingRepository.findByBookerIdAndStatusIs(
                        userId, Status.WAITING, pageable),
                RequestState.REJECTED, () -> bookingRepository.findByBookerIdAndStatusIs(
                        userId, Status.REJECTED, pageable)
        );
        return strategyMap.get(state).get();
    }

    private Page<Booking> getPageOfBookingsForOwnerByState(Long userId, Pageable pageable, RequestState state) {
        Map<RequestState, Supplier<Page<Booking>>> strategyMap = Map.of(
                RequestState.ALL, () -> bookingRepository.findByItemOwnerId(userId, pageable),
                RequestState.CURRENT, () -> bookingRepository.findByItemOwnerIdAndStartIsBeforeAndEndIsAfter(
                        userId, LocalDateTime.now(), LocalDateTime.now(), pageable),
                RequestState.FUTURE, () -> bookingRepository.findByItemOwnerIdAndStartIsAfterAndEndIsAfter(
                        userId, LocalDateTime.now(), LocalDateTime.now(), pageable),
                RequestState.PAST, () -> bookingRepository.findByItemOwnerIdAndStartIsBeforeAndEndIsBefore(
                        userId, LocalDateTime.now(), LocalDateTime.now(), pageable),
                RequestState.WAITING, () -> bookingRepository.findByItemOwnerIdAndStatusIs(
                        userId, Status.WAITING, pageable),
                RequestState.REJECTED, () -> bookingRepository.findByItemOwnerIdAndStatusIs(
                        userId, Status.REJECTED, pageable)
        );
        return strategyMap.get(state).get();
    }
}
